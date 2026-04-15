# Scénario D — perte du tracker sans restart broker (topic unload)

**Broker testé** : `apachepulsar/pulsar:2.11.0` (container `bug-pulsar`).
**Client** : `pulsar-client 2.11.0`. Ce scénario est dupliqué à l'identique
sur Pulsar 3.3.9 dans [scenario-v3.md](./scenario-v3.md) — les deux
versions perdent le tracker de la même manière.

## Pourquoi ce scénario existe

Les scénarios A et B sur-contraignent la reproduction : ils ferment client +
consumer **et** restartent le conteneur Pulsar. L'effet observable (perte du
compteur de redelivery) est réel, mais le framing "restart simultané" laisse
entendre que le bug nécessite un double incident — ce qui est faux.

`InMemoryRedeliveryTracker` est un `ConcurrentHashMap<PositionImpl, Integer>`
attaché au **dispatcher** du cursor de la subscription. Il disparaît dès que
le dispatcher est fermé, peu importe la cause :

- restart broker (scénario A)
- restart du dernier consumer de la subscription
- **bundle unload / rebalancing** (routine, sans downtime)
- **topic unload manuel ou automatique** (ce scénario)

Ce scénario isole la cause minimale : **un simple `pulsar-admin topics unload`,
sans redémarrer le broker**, suffit à remettre le compteur à zéro et à empêcher
la DLQ d'être atteinte. C'est ce qui arrive en prod à chaque rebalancing de
bundle.

## Ce que le test fait

[`src/test/java/com/test/pulsar/ScenarioDTest.java`](../src/test/java/com/test/pulsar/ScenarioDTest.java)

1. Publie 10 messages poison sur un topic unique (UUID).
2. Souscrit un `NackConsumer` avec la même config que A
   (`negativeAcknowledge` sans `enableRetry`, backoff 1s → 60s, max = 10).
3. Laisse tourner 9 s — suffisant pour que les messages aient été redélivrés
   jusqu'à `redeliveryCount = 3` (backoff cumulé 1 + 2 + 4 = 7 s).
4. Capture `maxRedeliveryCountSeen` (doit être ≥ 2) et snapshotte le compteur
   de messages reçus.
5. **Exécute `docker exec bug-pulsar bin/pulsar-admin topics unload <topic>`**
   via `ProcessBuilder`. Le broker reste up. Pulsar ferme le dispatcher, détruit
   l'`InMemoryRedeliveryTracker`, puis recharge le topic. Le consumer se
   réattache transparent (aucune action côté client).
6. Laisse tourner 5 s post-unload puis vérifie que `lastRedeliveryCountSeen`
   (le rc de la dernière livraison reçue) est **strictement inférieur** au max
   pré-unload — preuve que le nouveau dispatcher repart d'un tracker vierge.
7. Laisse tourner 20 s supplémentaires puis vérifie que la DLQ est toujours
   vide (`msgInCounter == 0`).

### Pourquoi `lastRedeliveryCountSeen` et pas `maxRedeliveryCountSeen`

`maxRedeliveryCountSeen` est monotone sur la durée de vie du consumer — il ne
redescend jamais, quels que soient les unload. Le signal fort est `lastRc`,
parce qu'il reflète la dernière livraison vue par le listener. Si le tracker
a été reset, les premières livraisons post-unload arrivent avec `rc = 0`,
puis `1`, puis `2`… en repartant de zéro indépendamment de l'historique.

Pour soutenir cette vérification, `NackConsumer` expose
`getLastRedeliveryCountSeen()` en plus du max historique.

## Résultat observé (run du 2026-04-15)

```
=== NackConsumer subscribed topic=persistent://public/default/test-nack-unload-scenario-b37ca58d
Received id=165:0:-1 redeliveryCount=0 payload=poison-0
...
Received id=165:9:-1 redeliveryCount=0
Received id=165:0:-1 redeliveryCount=1
...
Received id=165:9:-1 redeliveryCount=3
=== Avant unload : maxRedeliveryCountSeen=3 messagesReceived=40
=== pulsar-admin topics unload persistent://public/default/test-nack-unload-scenario-b37ca58d
=== topic unloaded
Received id=165:0:-1 redeliveryCount=0 payload=poison-0      ← REPARTI DE ZÉRO
Received id=165:1:-1 redeliveryCount=0
...
Received id=165:9:-1 redeliveryCount=0
Received id=165:0:-1 redeliveryCount=1
...
Received id=165:9:-1 redeliveryCount=2
=== Après unload : lastRedeliveryCountSeen=2 messagesReceivedSinceUnload=30
Received id=165:0:-1 redeliveryCount=3
...
=== Après 20s supplémentaires : dlqMsgInCounter=0
```

Lecture :

- **Avant le unload**, les 10 messages ont été livrés 4 fois chacun
  (40 receptions, `rc` max = 3).
- À l'instant où `pulsar-admin topics unload` se termine (~4 s après son
  émission), **les mêmes MessageIds reviennent avec `rc = 0`** — prouvant que
  l'`InMemoryRedeliveryTracker` a été détruit et recréé vierge par Pulsar,
  sans aucune intervention côté client et sans restart du broker.
- 20 s plus tard la DLQ est toujours vide, comme dans le Scénario A.

## Ce que ce test change dans l'interprétation du bug

| Framing initial | Framing corrigé par D |
|---|---|
| Le bug nécessite un restart simultané broker + consumer | Le bug se déclenche à **chaque** fermeture du dispatcher de la subscription |
| Les 400k de prod viennent des déploiements hebdomadaires | Les 400k de prod viennent probablement du **load balancer** qui rebalance les bundles toutes les quelques minutes |
| Le correctif doit protéger contre les restarts | Le correctif doit protéger contre **tout** événement de cycle de vie du dispatcher |

Le correctif reste le même : `reconsumeLater` + `enableRetry(true)`, qui
persiste le compteur comme propriété du message dans BookKeeper et
l'immunise à tous les événements de la colonne de droite.

## Comment le lancer

```bash
docker-compose up -d
mvn test -Dtest=ScenarioDTest
```

Durée : ~40 s. Logs complets dans `target/test.log`.

## Limites de la repro

- Ce test utilise `topics unload` qui est l'équivalent *ciblé* d'un bundle
  unload. Un `namespaces unload` aurait le même effet sur tous les topics du
  namespace, ce qui est plus proche du rebalancing prod — mais inutile pour
  la démonstration sur un topic unique.
- On ne mesure pas directement la métrique prod `pulsar_topic_load_balancer_bundles_split_count`
  ni `bundleUnloading`. Le test reste une preuve de mécanisme, pas un estimateur
  de fréquence. Pour corréler à la prod, cf. la section "Ce qu'il faut surveiller"
  dans [README.md](./README.md#ce-quil-faut-surveiller-pour-confirmer).

## Fichiers clefs

| Fichier | Rôle |
|---|---|
| [`src/test/java/com/test/pulsar/ScenarioDTest.java`](../src/test/java/com/test/pulsar/ScenarioDTest.java) | Test JUnit 5, unload via `dockerExec` |
| [`src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java`](../src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java) | Nouveau helper `dockerExec(String...)` partagé |
| [`src/main/java/com/test/pulsar/consumer/NackConsumer.java`](../src/main/java/com/test/pulsar/consumer/NackConsumer.java) | `getLastRedeliveryCountSeen()` ajouté pour le signal fort du reset |
