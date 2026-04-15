# Scénario A — `negativeAcknowledge` sans `enableRetry` + restart simultané

## Bug reproduit

Quand un consumer Pulsar utilise `negativeAcknowledge` **sans** `enableRetry(true)`,
le compteur de redelivery est stocké à deux endroits *volatiles* :

| Compteur | Où | Durée de vie |
|---|---|---|
| `NegativeAcksTracker` | mémoire du **client** | détruit à la fermeture du consumer |
| `InMemoryRedeliveryTracker` | mémoire du **broker** (attaché au cursor) | détruit au restart du broker |

Résultat : si **broker et consumer redémarrent en même temps**, les deux trackers
sont perdus. Les messages reviennent avec `redeliveryCount = 0`, le seuil
`maxRedeliverCount` n'est jamais atteint, et **la DLQ n'est jamais alimentée**.
En prod, ça se traduit par un backlog qui gonfle à l'infini.

## Ce que le test fait

[`src/test/java/com/test/pulsar/ScenarioATest.java`](../src/test/java/com/test/pulsar/ScenarioATest.java)

1. Publie 10 messages "poison" sur un topic unique (UUID).
2. Souscrit un `NackConsumer` avec :
   - `subscriptionType(Shared)`
   - `deadLetterPolicy(maxRedeliverCount = 10)`
   - `negativeAckRedeliveryBackoff(1s → 60s, multiplier 2)`
   - **pas** de `enableRetry`
   - listener qui fait systématiquement `consumer.negativeAcknowledge(msg)`
3. Laisse tourner ~7 s (≈ 3 cycles de nack avec le backoff).
4. Ferme `client` + `consumer` **et** exécute `docker-compose restart pulsar`
   via `ProcessBuilder`. Attend ensuite `GET /admin/v2/brokers/health` jusqu'à 200.
5. Recrée client + consumer (compteurs remis à zéro).
6. Laisse tourner 3 s puis vérifie que le `maxRedeliveryCountSeen` post-restart
   est strictement inférieur à celui d'avant.
7. Laisse tourner 30 s supplémentaires puis vérifie que le topic DLQ a
   `msgInCounter == 0` (ou n'existe pas → 404 traité comme 0).

### Pourquoi un `msgInCounter` et pas un `msgBacklog` sur la DLQ

Un topic DLQ n'a pas de subscription tant que personne ne lit dedans ; son
`msgBacklog` reste à 0 même si des messages y ont été envoyés. `msgInCounter`
est le compteur cumulatif des messages jamais publiés sur le topic, ce qui est
l'assertion qu'on veut vraiment : "est-ce qu'un seul message a atteint la DLQ ?"

## Résultat observé (run du 2026-04-15)

```
=== Avant restart : maxRedeliveryCountSeen=2 messagesReceived=30
=== Avant restart : backlog subscription=10
NackConsumer closed
=== docker-compose restart pulsar
[docker-compose]  Container bug-pulsar  Restarting
[docker-compose]  Container bug-pulsar  Started
=== broker prêt après restart   (14 s plus tard)
NackConsumer subscribed ...
=== Après restart : maxRedeliveryCountSeen=1 messagesReceived=20
=== Après 30s supplémentaires : dlqMsgInCounter=0
```

Lecture :

- **Avant** le restart, les 10 messages ont déjà été livrés 3 fois chacun
  (`messagesReceived = 30`), et le `redeliveryCount` max vu côté client est **2**.
- **Après** le restart, le nouveau consumer re-reçoit les messages avec
  `redeliveryCount = 0` puis `1` — prouvant que le tracker côté broker a bien
  perdu son état pendant le restart.
- Les redeliveries continuent ensuite de monter (les logs montrent
  `redeliveryCount=5` à la fin du test), mais **toujours en repartant de zéro
  à chaque restart**. Le seuil 10 n'est jamais atteint et la DLQ reste vide.

## Comment le lancer

```bash
docker-compose up -d
# attendre que le healthcheck passe (~15-30 s la première fois)
mvn test -Dtest=ScenarioATest
```

Les logs complets du dernier run sont dans `target/test.log`.

## Pièges rencontrés pendant le scaffold

1. **Conflit de bindings SLF4J** — `pulsar-client 2.11.0` ramène
   `log4j-slf4j-impl`, qui gagne contre logback et ignore silencieusement
   `logback.xml`. Correctif : exclusion des trois artefacts `log4j-*` dans la
   dépendance `pulsar-client` du [`pom.xml`](../pom.xml).
2. **Version de logback** — Pulsar 2.11 impose `slf4j-api 1.7.x`. Logback 1.4.x
   et 1.3.x ciblent SLF4J 2.x et échouent avec
   `Failed to load class org.slf4j.impl.StaticLoggerBinder`. Correctif : fixer
   logback à `1.2.12`.
3. **Channel corruption de surefire** — `ProcessBuilder.inheritIO()` sur le
   sous-process `docker-compose` écrit des octets bruts sur le stdout du JVM
   forké, que surefire utilise comme canal reporter (`Corrupted channel by
   directly writing to native stream`). Correctif : drainer `getInputStream()`
   dans un `BufferedReader` et logger chaque ligne via SLF4J.
4. **Image Pulsar 2.11.0 arm64** — n'existe pas. Docker tire la variante
   `linux/amd64` et l'émule sous Rosetta sur Apple Silicon. Fonctionnel mais
   le démarrage du broker prend ~20 s au lieu de ~5 s.

## Limites de la repro

- Le test ferme le `PulsarClient` et restart le container, ce qui est l'équivalent
  fonctionnel d'un restart simultané (les deux trackers sont perdus). Un vrai
  restart simultané en prod implique aussi que la reconnexion du consumer tombe
  pendant la fenêtre d'indisponibilité du broker, ce que le test ne simule pas
  explicitement — mais l'effet observable (perte du tracker broker) est le même.
- Le seuil `maxRedeliverCount(10)` est celui de la prod. Le test démontre
  qu'après un restart on repart de 0 ; il ne démontre pas directement que
  l'accumulation est infinie sur N restarts successifs. C'est l'objet du
  **Scénario C**.

## Fichiers clefs

| Fichier | Rôle |
|---|---|
| [`src/main/java/com/test/pulsar/consumer/NackConsumer.java`](../src/main/java/com/test/pulsar/consumer/NackConsumer.java) | Consumer nack-only, tracke `maxRedeliveryCountSeen` |
| [`src/main/java/com/test/pulsar/producer/TestProducer.java`](../src/main/java/com/test/pulsar/producer/TestProducer.java) | Publish de messages poison |
| [`src/main/java/com/test/pulsar/util/PulsarMetrics.java`](../src/main/java/com/test/pulsar/util/PulsarMetrics.java) | Lecture backlog / `msgInCounter` via admin HTTP + `waitForBrokerReady` |
| [`src/test/java/com/test/pulsar/ScenarioATest.java`](../src/test/java/com/test/pulsar/ScenarioATest.java) | Test JUnit 5, restart broker via `ProcessBuilder` |
| [`docker-compose.yml`](../docker-compose.yml) | Pulsar 2.11.0 standalone |
