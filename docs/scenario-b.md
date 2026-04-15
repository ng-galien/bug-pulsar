# Scénario B — `reconsumeLater` avec `enableRetry(true)` + restart simultané

## Comportement démontré

Quand un consumer utilise `reconsumeLater(...)` avec `enableRetry(true)`,
le compteur de tentatives n'est **plus** stocké en mémoire côté broker. À la
place, le client :

1. Publie une nouvelle copie du message sur le **retry topic**
   (`{topic}-RETRY`) avec une propriété `RECONSUMETIMES` incrémentée et un
   `deliverAt` correspondant au délai demandé.
2. Acknowledge la livraison en cours (la version "actuelle" du message est
   considérée traitée).
3. Quand `RECONSUMETIMES > maxRedeliverCount`, publie sur le **DLQ topic**
   (`{topic}-DLQ`) au lieu du retry topic.

Parce que `RECONSUMETIMES` est persisté **comme propriété du message dans
BookKeeper**, il survit à n'importe quel restart : broker, consumer, ou les
deux simultanément. **La DLQ est donc toujours atteinte après N tentatives**,
contrairement au [Scénario A](./scenario-a.md).

## Ce que le test fait

[`src/test/java/com/test/pulsar/ScenarioBTest.java`](../src/test/java/com/test/pulsar/ScenarioBTest.java)

1. Publie 5 messages poison sur un topic unique.
2. Souscrit un `ReconsumeLaterConsumer` avec :
   - `subscriptionType(Shared)`
   - `enableRetry(true)` ← la clef
   - `deadLetterPolicy(maxRedeliverCount = 3, retryLetterTopic, deadLetterTopic)`
   - listener qui fait systématiquement `reconsumeLater(msg, 2, SECONDS)`
3. Laisse tourner 6 s (suffisant pour 2-3 cycles de reconsume).
4. Relève :
   - `maxReconsumeTimesSeen` (via `RECONSUMETIMES` côté client)
   - `msgInCounter` du retry topic (via admin HTTP)
5. Ferme `client` + `consumer` puis exécute `docker-compose restart pulsar`.
6. Attend que le broker soit prêt (`GET /admin/v2/brokers/health`).
7. Recrée client + consumer.
8. Laisse tourner 15 s pour que les retries en attente se déclenchent et que
   le seuil soit atteint.
9. Vérifie :
   - `maxReconsumeTimesSeen` post-restart **≥** pré-restart (le compteur a
     bien été préservé)
   - `msgInCounter` du DLQ topic **≥ 5** (au moins un passage DLQ par
     message d'origine)

## Résultat observé (run du 2026-04-15)

```
=== Avant restart : maxReconsumeTimesSeen=2 messagesReceived=15
=== Avant restart : retry topic msgInCounter=15
ReconsumeLaterConsumer closed
=== docker-compose restart pulsar
[docker-compose]  Container bug-pulsar  Restarting
[docker-compose]  Container bug-pulsar  Started
=== broker prêt après restart   (13 s plus tard)
ReconsumeLaterConsumer subscribed ...
Received id=80:0:-1 RECONSUMETIMES=1 ...   ← pas de retour à zéro !
Received id=80:1:-1 RECONSUMETIMES=2 ...
Received id=80:6:-1 RECONSUMETIMES=3 ...
=== Après restart : maxReconsumeTimesSeen=3 messagesReceived=37
=== Après restart : dlqMsgInCounter=16
```

Lecture :

- **Avant** le restart, les 5 messages ont été reconsumed deux fois chacun
  (15 livraisons cumulées, `RECONSUMETIMES` max = 2).
- Le retry topic contient 15 messages (5 publications initiales × 3
  occurrences successives avec `RECONSUMETIMES=0,1,2`).
- **Après** le restart, les livraisons reprennent immédiatement avec
  `RECONSUMETIMES=1, 2, 3` — **le compteur n'a pas été remis à zéro**.
- `maxReconsumeTimesSeen` monte à **3** (le plafond `maxRedeliverCount`).
- **La DLQ est atteinte** : `dlqMsgInCounter = 16`.

## Pourquoi la DLQ contient 16 messages et pas 5

On a publié 5 messages poison, mais la DLQ en reçoit **16**. Ce n'est pas un
bug du test : c'est une conséquence de la sémantique at-least-once de Pulsar
quand le consumer ferme au milieu du traitement.

Chaîne d'événements :

1. Avant le restart, plusieurs messages étaient *en cours* de `reconsumeLater`
   (acknowledge de la version courante + publish de la version suivante).
   Certains de ces acks n'ont pas eu le temps de remonter au broker avant le
   close.
2. Après le restart, le broker redélivre ces messages (non-acked) sur le
   retry topic → le listener les retraite → chaque retraitement crée une
   **nouvelle** copie sur le retry/DLQ topic avec `RECONSUMETIMES` +1.
3. Quand `RECONSUMETIMES` a déjà atteint 3, le retraitement envoie directement
   en DLQ — d'où les 16 entrées (5 originaux × ~3 duplications).

Ce n'est pas un problème pour la démonstration : **l'objectif est de prouver
que la DLQ est atteinte**, ce qui est strictement impossible dans le Scénario
A. Le nombre exact n'est pas significatif — l'assertion du test est
`dlqMsgInCounter >= POISON_COUNT`.

Pour obtenir un compte exact dans un vrai système de prod il faudrait soit
une logique d'idempotence côté consommateur (déduplication sur `MessageId`
ou sur une clef métier), soit une clef de partition (`SubscriptionType.Key_Shared`)
qui sérialise le traitement par clef.

## Comment le lancer

```bash
docker-compose up -d
mvn test -Dtest=ScenarioBTest
```

Logs complets dans `target/test.log`.

## Différence A ↔ B — résumé

| | Scénario A (`nack`) | Scénario B (`reconsumeLater` + `enableRetry`) |
|---|---|---|
| Compteur stocké dans | `InMemoryRedeliveryTracker` (broker RAM) | Propriété `RECONSUMETIMES` (message BK) |
| Survit au close consumer | ❌ | ✅ |
| Survit au restart broker | ❌ | ✅ |
| DLQ atteinte après restart | ❌ jamais | ✅ après N tentatives |
| Effet en prod | backlog qui gonfle à l'infini | duplication possible mais terminaison garantie |

## Limites de la repro

- La duplication en DLQ est *attendue* sur un restart simultané : le test ne
  cherche pas à l'éliminer. Pour un comportement exactement-once il faudrait
  des transactions Pulsar (2.8+) ou de la déduplication applicative.
- Les valeurs `maxRedeliverCount=3` et `delay=2s` sont réduites pour
  accélérer le test ; la prod utilise 10 et 30 s.

## Fichiers clefs

| Fichier | Rôle |
|---|---|
| [`src/main/java/com/test/pulsar/consumer/ReconsumeLaterConsumer.java`](../src/main/java/com/test/pulsar/consumer/ReconsumeLaterConsumer.java) | Consumer `enableRetry(true)` + reconsumeLater, tracke `maxReconsumeTimesSeen`. Constantes `MAX_REDELIVER_COUNT` / `RECONSUME_DELAY_SECONDS` publiques pour coupler les durées d'attente du test. |
| [`src/main/java/com/test/pulsar/config/TopicNames.java`](../src/main/java/com/test/pulsar/config/TopicNames.java) | Record qui centralise main/subscription/retry/dlq |
| [`src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java`](../src/test/java/com/test/pulsar/AbstractPulsarScenarioTest.java) | Base class partagée avec A et C |
| [`src/test/java/com/test/pulsar/ScenarioBTest.java`](../src/test/java/com/test/pulsar/ScenarioBTest.java) | Test JUnit 5 avec restart broker ; `POST_RESTART_DRAIN_WINDOW` calculé à partir des constantes du consumer |
