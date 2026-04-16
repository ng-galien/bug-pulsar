# bug-pulsar

Projet de reproduction d'un bug Pulsar (`negativeAcknowledge` sans `enableRetry` = la DLQ n'est jamais atteinte dès que le dispatcher est fermé, peu importe la cause).
Quatre scénarios A/B/C/D sur Pulsar 2.11.0 + scénarios V3 (Pulsar 3.3.9) et V4 (Pulsar 4.2.0), documentés dans `docs/`. **D** est la preuve minimale (unload suffit, pas besoin de restart). **V3** et **V4** prouvent que le bug survit à l'upgrade broker sur les trois lignes majeures.

## Run

- `docker-compose up -d` — démarre Pulsar 2.11.0 standalone (container `bug-pulsar`)
- `docker-compose --profile v3 up -d pulsar-v3` — démarre *en plus* Pulsar 3.3.9 (container `bug-pulsar-v3`, ports 6651/8081)
- `docker-compose --profile v4 up -d pulsar-v4` — démarre *en plus* Pulsar 4.2.0 (container `bug-pulsar-v4`, ports 6652/8082)
- `docker inspect -f '{{.State.Health.Status}}' bug-pulsar` — check healthcheck
- `mvn test -Dtest=ScenarioATest` — un scénario ciblé (A, B, C, D, ScenarioV3UnloadTest ou ScenarioV4UnloadTest)
- `mvn test` — les six si les trois brokers sont up. V3/V4 sont skippés via `@EnabledIf` si leurs brokers ne sont pas démarrés (pas d'erreur).
- `mvn -Pclient-v3 test -Dtest=ScenarioV3UnloadTest` — rejoue V3 avec `pulsar-client 3.3.9` au lieu de 2.11.0. Le profile swap aussi logback 1.2.12 → 1.5.6 (slf4j 2.x requis par le client 3.x). Conclusion mesurée : le bug persiste aussi avec le client 3.x.
- `mvn -Pclient-v4 test -Dtest=ScenarioV4UnloadTest` — rejoue V4 avec `pulsar-client 4.2.0`. Même conclusion : le bug persiste avec le client 4.x.
- Admin HTTP V2 : `http://localhost:8080` / V3 : `http://localhost:8081` / V4 : `http://localhost:8082`
- Broker V2 : `pulsar://localhost:6650` / V3 : `pulsar://localhost:6651` / V4 : `pulsar://localhost:6652`

## Dépendances — pièges

- `pulsar-client 2.11.0` ramène `log4j-slf4j-impl` qui gagne contre logback. Les trois exclusions log4j-* dans `pom.xml` sont obligatoires.
- Pulsar 2.11 force `slf4j-api 1.7.x` → logback doit rester en **1.2.12** (1.3.x/1.4.x ciblent slf4j 2.x et échouent sur `StaticLoggerBinder`).
- Sur Apple Silicon, l'image `apachepulsar/pulsar:2.11.0` tourne sous Rosetta (pas d'arm64 officiel) — healthcheck ~20 s au lieu de ~5 s.
- Warning `sun.net.InetAddressCachePolicy` au démarrage client : bénin, fallback JPMS OK.
- Pulsar 4.x n'advertise plus `localhost` par défaut dans Docker — il faut `PULSAR_PREFIX_advertisedAddress=localhost` ou injecter la config dans `standalone.conf` (cf. `docker-compose.yml` service `pulsar-v4`).

## Tests — patterns

- Topic et subscription suffixés par `UUID.randomUUID().toString().substring(0, 8)` via `TopicNames.forTest(prefix)` — isolation entre runs.
- Logs complets dans `target/test.log` (FileAppender logback, écrasé à chaque run) — `grep` dedans plutôt que `mvn test | tail` qui tronque.
- Ne jamais `inheritIO()` sur un `ProcessBuilder` depuis un test surefire : corrompt le canal reporter JVM forké. Drainer `getInputStream()` ligne par ligne vers SLF4J.
- Restart broker : `docker-compose restart pulsar` via `ProcessBuilder`, puis attendre `waitForBrokerReady(timeout)` (instance, utilise `endpoint().adminUrl()`).
- Commandes in-container : `dockerExecInBroker("bin/pulsar-admin", "topics", "unload", topic)` — prend le nom de container depuis `endpoint().container()`, draine stdout. Surcharge statique `dockerExec(container, cmd...)` disponible pour cibler un autre container.
- Multi-broker : `PulsarEndpoint.V2` / `V3` / `V4` centralisent `(container, serviceUrl, adminUrl)`. Pour tester contre un autre broker, override `protected PulsarEndpoint endpoint()` dans le test. `PulsarMetrics.getMsgInCounter/getBacklog/isBrokerReady` ont des overloads qui prennent un `adminUrl` explicite ; les helpers d'instance `getMsgInCounter(topic)` / `getBacklog(topic, sub)` de `AbstractPulsarScenarioTest` utilisent automatiquement `endpoint().adminUrl()`.
- Skip conditionnel : `@EnabledIf("isXxxBrokerReady")` sur la classe pour qu'un `mvn test` ne casse pas si un broker optionnel (ex : V3) n'est pas up.
- DLQ : utiliser `PulsarMetrics.getMsgInCounter(dlqTopic)` — **pas** `msgBacklog` (le topic DLQ n'a pas de subscription, son backlog reste à 0).
- Consumers acceptent un `Predicate<Message<String>>` pour mixer good/poison (Scénario C).
- `NackConsumer.getLastRedeliveryCountSeen()` expose le rc de la dernière delivery (monotone non-garanti), différent de `getMaxRedeliveryCountSeen()` qui est monotone. Le premier est nécessaire pour observer un reset du tracker sans fermer le consumer (Scénario D).

## Docs

- `docs/README.md` — résumé exécutif + tableau A/B/C/D + note V3
- `docs/scenario-{a,b,c,d}.md` — détail par scénario sur Pulsar 2.11.0
- `docs/scenario-v3.md` — rejoue D contre Pulsar 3.3.9, prouve que l'upgrade broker ne corrige pas
- Scénario V4 — rejoue D contre Pulsar 4.2.0, même conclusion
