# Scénario C — accumulation continue du backlog

## Comportement démontré

Sous un flux continu de messages avec un taux d'échec fixe (50 %), le backlog
de la subscription principale diverge complètement selon la stratégie de
retry :

- **`negativeAcknowledge`** : les messages poison restent dans le cursor de
  la subscription principale en attente de redelivery. Tant qu'ils n'ont pas
  atteint `maxRedeliverCount`, ils comptent dans `msgBacklog`. Avec un backoff
  multiplicatif (1s → 60s), un message poison passe plusieurs minutes dans le
  backlog avant DLQ (en régime nominal) — et **n'y arrive jamais** si un
  restart broker+consumer intervient entre-temps (cf. [Scénario A](./scenario-a.md)).
- **`reconsumeLater` + `enableRetry(true)`** : le client *acknowledge*
  immédiatement la livraison courante sur la subscription principale et
  republie une copie sur le retry topic. Le backlog de la subscription
  principale reste donc drainé, indépendamment du taux d'échec.

Scénario C mesure cette divergence **sans restart** — c'est le régime
nominal, pas le scénario de panne.

## Ce que le test fait

[`src/test/java/com/test/pulsar/ScenarioCTest.java`](../src/test/java/com/test/pulsar/ScenarioCTest.java)

Deux `@Test` dans la même classe, chacun avec son topic unique :

1. **`backlogGrowsUnboundedWithNack`** — `NackConsumer` avec prédicat
   `isGoodMessage` (ack si le payload commence par `good-`, nack sinon).
2. **`backlogStabilizesWithReconsumeLater`** — `ReconsumeLaterConsumer` avec
   le même prédicat.

Les deux tests partagent la même structure :

1. Publisher thread démarré sur un `Executors.newSingleThreadExecutor()`,
   publie à **10 msg/s** en alternance `good-N` / `poison-N` (50/50).
2. Thread principal échantillonne `subscription.msgBacklog` via l'admin HTTP
   toutes les **10 secondes** pendant **90 secondes** (9 échantillons).
3. Arrêt publisher + fermeture consumer.
4. Assertions :
   - Nack : `last > 100` **et** `last > 2 × first` (croissance significative)
   - ReconsumeLater : `max < 100` **et** `last < 100` (stabilité)

### Pourquoi 10 msg/s et 90 s

Le spec d'origine prévoyait 1 msg/s × 5 min. On a accéléré pour que le test
tienne dans `mvn test` (< 4 min total pour les deux @Test). Les résultats
restent très lisibles : à 5 poison/s le backlog croît de ~50 toutes les 10
secondes, soit une progression linéaire très propre sur 9 points.

## Résultat observé (run du 2026-04-15)

### `backlogGrowsUnboundedWithNack`

```
t= 10s published=101  backlog= 51
t= 20s published=201  backlog=100
t= 30s published=301  backlog=150
t= 40s published=402  backlog=201
t= 50s published=503  backlog=252
t= 60s published=604  backlog=302
t= 70s published=704  backlog=352
t= 80s published=805  backlog=403
t= 90s published=905  backlog=452
=== Nack run — acked=453 nacked=2555 received=3008
```

- **Croissance linéaire parfaite** : ~50 / 10 s = 5 poison/s absorbés dans le
  backlog. Aucun plateau, aucune décroissance.
- `received = 3008` pour `published = 905` → chaque poison a été livré en
  moyenne ~5.6 fois pendant les 90 s (redeliveries comprises).
- Aucun message n'a atteint `maxRedeliverCount=10` pendant le test : le
  premier poison n'est publié qu'à t=0.2s et a subi des backoffs
  1+2+4+8+16+32+60 ≈ 123 s avant d'être éligible au 8ᵉ delivery — donc pas
  encore passé en DLQ à t=90s.

### `backlogStabilizesWithReconsumeLater`

```
t= 10s published=103  backlog=1
t= 20s published=203  backlog=1
t= 30s published=303  backlog=0
t= 40s published=404  backlog=1
t= 50s published=505  backlog=1
t= 60s published=605  backlog=1
t= 70s published=705  backlog=0
t= 80s published=805  backlog=0
t= 90s published=905  backlog=0
=== ReconsumeLater run — acked=453 reconsumed=1778 received=2231
```

- **Backlog plat à 0-1** pendant toute la durée, alors même que 5 poison/s
  arrivent en continu. Les valeurs 0 ou 1 correspondent à des messages
  in-flight (échantillonnés juste avant un ack).
- `reconsumed = 1778` : les messages poison ont été reconsumed en moyenne
  ~3.9 fois avant d'atteindre DLQ (`maxRedeliverCount=3`).
- `received = 2231` vs `published = 905` → ratio plus bas que pour le nack
  run, parce que chaque reconsumption crée un nouveau message sur le retry
  topic plutôt qu'un redelivery de l'original.

## Lecture métier

Tes 400 000 messages de backlog observés en prod sont une conséquence
mécanique de deux paramètres :

- taux d'arrivée poison (nombre de messages/s qui échouent)
- durée moyenne d'un cycle de nack avant DLQ (qui dépend du backoff et de
  `maxRedeliverCount`)

Dans le régime nominal, `msgBacklog ≈ poison_rate × avg_cycle_duration`. Si
un restart simultané broker+consumer remet le compteur à 0 ([Scénario A](./scenario-a.md)),
`avg_cycle_duration` tend vers l'infini et le backlog aussi.

Le fix est le même pour les deux : **passer à `reconsumeLater + enableRetry(true)`**.

## Comment le lancer

```bash
docker-compose up -d
mvn test -Dtest=ScenarioCTest
```

Durée : ~3 min 30 (90 s par test + setup/teardown).

Logs complets dans `target/test.log`. Les lignes d'intérêt sont filtrables
avec `grep "ScenarioCTest"`.

## Pièges rencontrés pendant l'écriture du test

1. **Ordre d'exécution JUnit 5** — JUnit 5 ne garantit pas l'ordre des `@Test`
   par défaut. Dans ce run, `backlogStabilizesWithReconsumeLater` a tourné
   avant `backlogGrowsUnboundedWithNack`. Les deux sont indépendants (topic
   unique par test) donc ça n'a pas d'impact, mais si on voulait forcer
   l'ordre il faudrait `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`.
2. **Pipe `tail -60` sur `mvn test`** — le premier run a été lancé avec un
   pipe qui a tronqué toute la sortie sauf la queue du deuxième test,
   masquant les deux ensembles de samples. Le file appender logback écrit
   dans `target/test.log` (cf. scénario A), ce qui donne la sortie complète
   indépendamment du pipe.
3. **Warning `sun.net.InetAddressCachePolicy`** — Pulsar client 2.11 tente un
   accès réflectif bloqué par JPMS sur Java 17+. Fallback silencieux sur les
   défauts système, aucun impact fonctionnel. On pourrait le taire avec
   `--add-opens java.base/sun.net=ALL-UNNAMED` sur la JVM surefire mais ça
   n'en vaut pas la peine.

## Limites de la repro

- Le test démontre la **forme** de la croissance (linéaire) sur 90 s mais
  pas le régime stationnaire avec DLQ. Pour voir la décroissance finale du
  backlog nack quand les premiers messages atteignent enfin DLQ, il faudrait
  prolonger à ~180 s et observer le plafonnement. Sans intérêt pour la
  démonstration principale — le plafond est `poison_rate × avg_cycle`, pas 0.
- Les deux tests utilisent `maxRedeliverCount=10` pour le nack et `3` pour le
  reconsumeLater (hérités des consumers). C'est cohérent avec les scénarios A
  et B mais rend la comparaison des volumes (nacked=2555 vs reconsumed=1778)
  non directement comparable en nombre. Ce qui compte c'est **la forme de la
  courbe backlog**, pas les totaux.

## Fichiers clefs

| Fichier | Rôle |
|---|---|
| [`src/test/java/com/test/pulsar/ScenarioCTest.java`](../src/test/java/com/test/pulsar/ScenarioCTest.java) | Les deux tests + boucle publisher/sampler |
| [`src/main/java/com/test/pulsar/consumer/NackConsumer.java`](../src/main/java/com/test/pulsar/consumer/NackConsumer.java) | Constructeur avec `Predicate<Message<String>>` |
| [`src/main/java/com/test/pulsar/consumer/ReconsumeLaterConsumer.java`](../src/main/java/com/test/pulsar/consumer/ReconsumeLaterConsumer.java) | Idem |
| [`src/main/java/com/test/pulsar/producer/TestProducer.java`](../src/main/java/com/test/pulsar/producer/TestProducer.java) | Méthode `send(String)` pour le streaming |
