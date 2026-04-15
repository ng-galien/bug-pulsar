# Scénario V3 — le bug persiste sur Pulsar 3.3.9

## Question posée

Le bug de reset du tracker a été caractérisé contre Pulsar **2.11.0** dans les
scénarios A à D. Question naturelle qui tombe dès qu'on présente la repro à
une équipe ops : *« et si on upgrade le broker, ça corrige ? »*

Pulsar a sorti plusieurs versions majeures depuis la 2.11 (3.0 LTS, 3.1, 3.2,
3.3, 4.0…). S'il existait un fix upstream pour l'`InMemoryRedeliveryTracker`
(persistance, rebuild automatique au bundle reload, etc.), ce scénario le
révélerait. Spoiler : **il n'y en a pas** en 3.3.9.

## Ce que le test fait

[`src/test/java/com/test/pulsar/ScenarioV3UnloadTest.java`](../src/test/java/com/test/pulsar/ScenarioV3UnloadTest.java)

Rejoue exactement le [Scénario D](./scenario-d.md) (topic unload sans restart
broker) mais contre un second conteneur `bug-pulsar-v3` qui tourne **Pulsar
3.3.9** en standalone. Tout le reste est identique :

- même `NackConsumer` (negativeAcknowledge sans enableRetry, backoff 1s → 60s,
  max = 10)
- même producer (10 messages poison)
- même séquence : 9 s de nack → `pulsar-admin topics unload` → 5 s
  d'observation → 20 s de vérification DLQ vide
- même assertion forte : `lastRedeliveryCountSeen < maxBeforeUnload`

Le **client Pulsar reste en 2.11.0**. Broker et client sont wire-compatibles
et on veut isoler la question "est-ce que le broker 3.x gère différemment
l'état du tracker ?" indépendamment de toute évolution du client.

## Infrastructure

`docker-compose.yml` contient un second service `pulsar-v3` dans un profile
Docker Compose dédié :

```yaml
pulsar-v3:
  image: apachepulsar/pulsar:3.3.9
  container_name: bug-pulsar-v3
  ports:
    - "6651:6650"
    - "8081:8080"
  profiles: ["v3"]
```

Le profile `v3` empêche le service de démarrer sur un simple `docker-compose
up -d` — il faut explicitement :

```bash
docker-compose --profile v3 up -d pulsar-v3
```

Côté Java, `PulsarEndpoint.V3` contient `("bug-pulsar-v3",
"pulsar://localhost:6651", "http://localhost:8081")`. `AbstractPulsarScenarioTest`
expose un hook `protected PulsarEndpoint endpoint()` que `ScenarioV3UnloadTest`
override pour retourner `V3` — toute la plomberie (création de client, admin
HTTP pour `getMsgInCounter`, `docker exec bug-pulsar-v3 ...`) bascule
automatiquement.

### Skip automatique si V3 pas démarré

`ScenarioV3UnloadTest` porte `@EnabledIf("isV3BrokerReady")`. La méthode
vérifie que `http://localhost:8081/admin/v2/brokers/health` répond 200 avant
d'instancier la classe. Si V3 n'est pas démarré, toute la classe est **skipped**
(pas erreur), donc un `mvn test` sur une machine sans V3 ne se casse pas.

## Résultat observé (run du 2026-04-15)

```
=== [V3] NackConsumer subscribed topic=persistent://public/default/test-nack-unload-v3-...
Received id=11:0:-1 redeliveryCount=0 payload=poison-0
Received id=11:1:-1 redeliveryCount=0
... (10 messages à rc=0)
Received id=11:0:-1 redeliveryCount=1
... (10 × rc=1)
Received id=11:0:-1 redeliveryCount=2
... (10 × rc=2)
Received id=11:0:-1 redeliveryCount=3
... (10 × rc=3)
=== [V3] Avant unload : maxRedeliveryCountSeen=3 messagesReceived=40
=== [V3] pulsar-admin topics unload persistent://public/default/test-nack-unload-v3-...
=== [V3] topic unloaded
Received id=11:0:-1 redeliveryCount=0 payload=poison-0      ← REPARTI DE ZÉRO
Received id=11:1:-1 redeliveryCount=0
... (10 × rc=0)
Received id=11:0:-1 redeliveryCount=1
... (10 × rc=1)
Received id=11:0:-1 redeliveryCount=2
... (10 × rc=2)
=== [V3] Après unload : lastRedeliveryCountSeen=2 messagesReceivedSinceUnload=30
Received id=11:0:-1 redeliveryCount=3
Received id=11:0:-1 redeliveryCount=4
=== [V3] Après 20s supplémentaires : dlqMsgInCounter=0
```

Lecture — **identique au scénario D en 2.11** :

- Avant le unload : 40 receptions, `rc` max = 3 (backoff naturel).
- `pulsar-admin topics unload` → mêmes MessageIds reviennent avec `rc = 0`.
  Pulsar 3.3.9 détruit et recrée l'`InMemoryRedeliveryTracker` **exactement
  comme 2.11.0**.
- Le consumer repart pour un cycle complet, DLQ reste vide 20 s plus tard.

## Conclusion

Aucune différence observable entre Pulsar 2.11.0 et 3.3.9 sur ce point précis.
L'upstream n'a pas persisté le tracker, pas changé son cycle de vie, pas rendu
le reset invisible côté client. **Upgrader le broker seul ne résout pas le
problème** ; il faut toujours basculer à `reconsumeLater + enableRetry(true)`
côté consommateur.

C'est un résultat attendu (la décision de design "tracker en RAM broker" est
profondément ancrée dans l'architecture du dispatcher) mais qu'il est
important d'avoir mesuré plutôt que supposé, surtout si une équipe envisage
un upgrade comme mitigation.

## Comment le lancer

```bash
docker-compose --profile v3 up -d pulsar-v3
# attendre que le healthcheck passe (~15 s)
mvn test -Dtest=ScenarioV3UnloadTest
```

Durée : ~40 s (similar à D sur 2.11). Logs complets dans `target/test.log`.

## Limites

- On n'a testé que **3.3.9**. Pulsar 4.x (actuellement 4.2.0) n'est pas
  couvert — pour la démonstration le signal fort est "le bug existe encore
  sur la dernière 3.x stable", ce qui suffit à décourager l'illusion d'un
  "upgrade-and-forget". Ajouter un `ScenarioV4UnloadTest` serait trivial :
  dupliquer la classe, changer `PulsarEndpoint.V3` en `V4`, ajouter un
  service `pulsar-v4` au `docker-compose.yml`.
- Comme pour D, le test prouve que le tracker est perdu au unload sans
  démontrer directement l'accumulation infinie. Le scénario C reste la
  référence pour ça, et sa conclusion (croissance linéaire du backlog) se
  transposerait à l'identique en V3.
- Le client reste en 2.11.0. Si jamais Pulsar client 3.x ajoutait un
  mécanisme *côté client* pour restaurer le compteur (par ex. en re-pushant
  localement un nack-with-count), ce test ne le capterait pas. En pratique,
  l'API `negativeAcknowledge` n'offre pas ce genre de surface — mais c'est
  une limite honnête de la méthodologie.

## Fichiers clefs

| Fichier | Rôle |
|---|---|
| [`docker-compose.yml`](../docker-compose.yml) | Service `pulsar-v3` sous profile `v3` |
| [`src/main/java/com/test/pulsar/config/PulsarEndpoint.java`](../src/main/java/com/test/pulsar/config/PulsarEndpoint.java) | Record avec constantes `V2` / `V3` — source unique des URLs et du nom de container |
| [`src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java`](../src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java) | Hook `endpoint()`, helpers `dockerExecInBroker`, `getMsgInCounter`, `waitForBrokerReady` désormais endpoint-aware |
| [`src/test/java/com/test/pulsar/ScenarioV3UnloadTest.java`](../src/test/java/com/test/pulsar/ScenarioV3UnloadTest.java) | Test JUnit 5, `@EnabledIf("isV3BrokerReady")`, override `endpoint() → V3` |
