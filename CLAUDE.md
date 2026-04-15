# bug-pulsar

Projet de reproduction d'un bug Pulsar (`negativeAcknowledge` sans `enableRetry` = la DLQ n'est jamais atteinte dès que le dispatcher est fermé, peu importe la cause).
Quatre scénarios A/B/C/D documentés dans `docs/`. **D** est la preuve minimale : unload d'un topic suffit, pas besoin de restart broker.

## Run

- `docker-compose up -d` — démarre Pulsar 2.11.0 standalone (container `bug-pulsar`)
- `docker inspect -f '{{.State.Health.Status}}' bug-pulsar` — check healthcheck
- `mvn test -Dtest=ScenarioATest` — un scénario ciblé (A, B, C ou D)
- `mvn test` — les quatre (~9 min total)
- Admin HTTP : `http://localhost:8080`, broker : `pulsar://localhost:6650`

## Dépendances — pièges

- `pulsar-client 2.11.0` ramène `log4j-slf4j-impl` qui gagne contre logback. Les trois exclusions log4j-* dans `pom.xml` sont obligatoires.
- Pulsar 2.11 force `slf4j-api 1.7.x` → logback doit rester en **1.2.12** (1.3.x/1.4.x ciblent slf4j 2.x et échouent sur `StaticLoggerBinder`).
- Sur Apple Silicon, l'image `apachepulsar/pulsar:2.11.0` tourne sous Rosetta (pas d'arm64 officiel) — healthcheck ~20 s au lieu de ~5 s.
- Warning `sun.net.InetAddressCachePolicy` au démarrage client : bénin, fallback JPMS OK.

## Tests — patterns

- Topic et subscription suffixés par `UUID.randomUUID().toString().substring(0, 8)` via `TopicNames.forTest(prefix)` — isolation entre runs.
- Logs complets dans `target/test.log` (FileAppender logback, écrasé à chaque run) — `grep` dedans plutôt que `mvn test | tail` qui tronque.
- Ne jamais `inheritIO()` sur un `ProcessBuilder` depuis un test surefire : corrompt le canal reporter JVM forké. Drainer `getInputStream()` ligne par ligne vers SLF4J.
- Restart broker : `docker-compose restart pulsar` via `ProcessBuilder`, puis attendre `PulsarMetrics.isBrokerReady()` (via Awaitility ou équivalent).
- Commandes in-container : `AbstractPulsarScenarioTest.dockerExec("bin/pulsar-admin", "topics", "unload", topic)` — utilise `docker exec bug-pulsar ...`, draine stdout pour ne pas corrompre surefire.
- DLQ : utiliser `PulsarMetrics.getMsgInCounter(dlqTopic)` — **pas** `msgBacklog` (le topic DLQ n'a pas de subscription, son backlog reste à 0).
- Consumers acceptent un `Predicate<Message<String>>` pour mixer good/poison (Scénario C).
- `NackConsumer.getLastRedeliveryCountSeen()` expose le rc de la dernière delivery (monotone non-garanti), différent de `getMaxRedeliveryCountSeen()` qui est monotone. Le premier est nécessaire pour observer un reset du tracker sans fermer le consumer (Scénario D).

## Docs

- `docs/README.md` — résumé exécutif + tableau A/B/C/D
- `docs/scenario-{a,b,c,d}.md` — détail par scénario (bug, code, résultats, pièges)
