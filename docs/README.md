# bug-pulsar — reproduction du flood de DLQ manquante

Projet de test qui reproduit et quantifie un bug Pulsar observé en production :
un backlog de ~400 000 messages qui ne passe jamais en DLQ, à cause d'une
interaction entre `negativeAcknowledge` et le restart simultané du broker et
du consumer.

## TL;DR

- **Bug** : `negativeAcknowledge` *sans* `enableRetry(true)` stocke le
  compteur de redelivery uniquement en RAM (client + broker). **Tout événement
  qui ferme le dispatcher de la subscription** — restart broker, restart
  consumer, bundle rebalancing, unload d'un topic — détruit l'état. La DLQ
  n'est jamais atteinte et le backlog gonfle.
- **Fix** : passer à `reconsumeLater(...)` avec `enableRetry(true)`. Le
  compteur `RECONSUMETIMES` est persisté comme propriété du message dans
  BookKeeper et survit à n'importe quel événement de cycle de vie.
- **Le bug existe aussi en Pulsar 3.3.9 et 4.2.0**. Les scénarios V3 et V4
  le vérifient contre des brokers standalone 3.3.9 et 4.2.0 respectivement.
  Le scénario V3 a aussi été testé avec `pulsar-client 3.3.9` (profile Maven
  `client-v3`). Dans toutes les combinaisons testées, le reset au unload est
  identique et la DLQ reste vide. L'upstream n'a corrigé le mécanisme ni côté
  broker ni côté client — le correctif applicatif reste nécessaire après
  n'importe quel upgrade.
- **Preuves mesurées** : quatre scénarios principaux (A/B/C/D) contre un
  broker Pulsar 2.11.0 + scénarios V3 et V4 contre Pulsar 3.3.9 et 4.2.0.
- **Issue upstream** : le piège n'est pas documenté côté Apache Pulsar.
  Une issue *docs-only* a été filée à partir de ce repro :
  [apache/pulsar#25533](https://github.com/apache/pulsar/issues/25533).
  Le draft complet et les notes pour le suivi sont dans
  [`docs/upstream-issue-draft.md`](./upstream-issue-draft.md).

## Versions testées

| Composant | Version | Rôle |
|---|---|---|
| Broker **V2** | `apachepulsar/pulsar:2.11.0` | Cible principale. C'est la version qui tourne en prod et où le bug a été observé pour la première fois. Container `bug-pulsar`, ports 6650/8080. Scénarios A, B, C, D. |
| Broker **V3** | `apachepulsar/pulsar:3.3.9` | Dernière 3.x stable publiée (patch release de la branche 3.3, mars 2026). Container `bug-pulsar-v3`, ports 6651/8081, profile Docker Compose `v3`. Scénario V3 uniquement. |
| Broker **V4** | `apachepulsar/pulsar:4.2.0` | Dernière 4.x publiée (mars 2026). Container `bug-pulsar-v4`, ports 6652/8082, profile Docker Compose `v4`. Nécessite `advertisedAddress=localhost` (4.x n'advertise plus automatiquement `localhost`). Scénario V4 uniquement. |
| Pulsar **client** (défaut) | `org.apache.pulsar:pulsar-client:2.11.0` | Utilisé pour tous les scénarios A/B/C/D et la run par défaut du scénario V3. Les wire protocols 2.11 et 3.x sont compatibles, donc un client 2.11 parle sans problème à un broker 3.3.9 — ce qui permet d'isoler la question "est-ce que le *broker* 3.x traite différemment l'état du tracker ?" indépendamment du client. |
| Pulsar **client** (profile `client-v3`) | `org.apache.pulsar:pulsar-client:3.3.9` | Activé par `mvn -Pclient-v3 test -Dtest=ScenarioV3UnloadTest`. Remplace le client 2.11.0 dans le pom pour répondre à la question de suivi "et si on upgrade *aussi* le client ?". Détails dans [scenario-v3.md § Et avec le client Pulsar 3](./scenario-v3.md#et-avec-le-client-pulsar-3). |
| Pulsar **client** (profile `client-v4`) | `org.apache.pulsar:pulsar-client:4.2.0` | Activé par `mvn -Pclient-v4 test -Dtest=ScenarioV4UnloadTest`. Même logique que `client-v3` mais pour la branche 4.x. |
| Java | 17+ (testé sur Java 21 Temurin) | Requis par Pulsar 2.11 côté client **et** par Pulsar 3.3 côté broker (le broker 3.x refuse de démarrer sous Java 11). |
| Logback | `1.2.12` (défaut) ou `1.5.6` (profile `client-v3`) | Pulsar 2.11 force `slf4j-api 1.7.x` → logback 1.2.x. Pulsar client 3.x pull slf4j 2.x → logback 1.5.x. La variable `logback.version` bascule automatiquement via le profile. |

Les trois lignes majeures (2.x, 3.x, 4.x) sont couvertes. Le bug est
identique sur les trois.

## Tableau récapitulatif

| | Scénario A — nack + restart | Scénario B — reconsumeLater + restart | Scénario C — accumulation | Scénario D — unload sans restart | Scénario V3 — Pulsar 3 | Scénario V4 — Pulsar 4 |
|---|---|---|---|---|---|---|
| **Broker** | Pulsar **2.11.0** | Pulsar **2.11.0** | Pulsar **2.11.0** | Pulsar **2.11.0** | Pulsar **3.3.9** | Pulsar **4.2.0** |
| **Stratégie testée** | `negativeAcknowledge` sans `enableRetry` | `reconsumeLater` + `enableRetry(true)` | nack vs reconsumeLater sous flux continu | `negativeAcknowledge` sans `enableRetry` | `negativeAcknowledge` sans `enableRetry` | `negativeAcknowledge` sans `enableRetry` |
| **Durée du test** | ~60 s | ~30 s | ~3 min 30 (2 × 90 s) | ~40 s | ~40 s | ~40 s |
| **Événement appliqué** | `docker-compose restart pulsar` | `docker-compose restart pulsar` | aucun (régime nominal) | `pulsar-admin topics unload` (broker up) | `pulsar-admin topics unload` (broker up) | `pulsar-admin topics unload` (broker up) |
| **Compteur survit à l'événement** | ❌ remis à 0 | ✅ `RECONSUMETIMES` intact | n/a | ❌ remis à 0 | ❌ remis à 0 | ❌ remis à 0 |
| **DLQ atteinte** | ❌ jamais (0 messages) | ✅ 16 messages (5 publiés, duplication at-least-once) | n/a | ❌ jamais (0 messages) | ❌ jamais (0 messages) | ❌ jamais (0 messages) |
| **Backlog après 90 s** | n/a | n/a | nack **452** vs reconsumeLater **0** | n/a | n/a | n/a |
| **Assertion clef** | `countAfterRestart < countBeforeRestart` & `dlqMsgInCounter == 0` | `dlqMsgInCounter >= 5` après restart | nack croît linéairement, reconsumeLater reste plat | `lastRedeliveryCountSeen < maxBeforeUnload` & `dlqMsgInCounter == 0` | `lastRedeliveryCountSeen < maxBeforeUnload` & `dlqMsgInCounter == 0` | `lastRedeliveryCountSeen < maxBeforeUnload` & `dlqMsgInCounter == 0` |
| **Documentation** | [scenario-a.md](./scenario-a.md) | [scenario-b.md](./scenario-b.md) | [scenario-c.md](./scenario-c.md) | [scenario-d.md](./scenario-d.md) | [scenario-v3.md](./scenario-v3.md) | — |

**Le rôle du scénario D** : prouver que le bug ne dépend pas d'un restart
broker. Un simple topic unload (équivalent d'un bundle rebalancing en prod)
suffit à perdre le tracker et empêche la DLQ d'être atteinte. C'est le
mécanisme qui produit les 400k en régime nominal, pas les déploiements
hebdomadaires. Voir [scenario-d.md](./scenario-d.md) pour la démonstration.

**Le rôle des scénarios V3 / V4** : rejouer D contre **Pulsar 3.3.9** et
**4.2.0** pour savoir si un upgrade broker résout le problème. Résultat :
**non**, le mécanisme est identique sur les trois lignes majeures. Upgrader
le broker sans changer le code applicatif n'efface pas le bug. Voir
[scenario-v3.md](./scenario-v3.md).

## Où est stocké le compteur de tentatives

| Mécanisme | Stockage | Durée de vie |
|---|---|---|
| `NegativeAcksTracker` (client) | RAM JVM du consumer | détruit au `consumer.close()` |
| `InMemoryRedeliveryTracker` (broker) | RAM du broker, attaché au cursor | détruit au restart du broker |
| `RECONSUMETIMES` (retry topic) | **propriété du message dans BookKeeper** | persistée jusqu'au passage DLQ ou à l'ack |

Les deux premiers expliquent le bug A. Le troisième explique pourquoi B est
immunisé.

## Reproduction en une commande

```bash
docker-compose up -d          # Pulsar 2.11.0 standalone (amd64 sous Rosetta sur Apple Silicon)
mvn test                      # lance A + B + C + D (V3/V4 skip si brokers pas démarrés)
# ou bien ciblé :
mvn test -Dtest=ScenarioATest

# Pour aussi vérifier sur Pulsar 3.3.9 et 4.2.0 :
docker-compose --profile v3 up -d pulsar-v3
docker-compose --profile v4 up -d pulsar-v4
mvn test -Dtest=ScenarioV3UnloadTest                     # client 2.11 × broker 3.3.9
mvn test -Dtest=ScenarioV4UnloadTest                     # client 2.11 × broker 4.2.0
mvn -Pclient-v3 test -Dtest=ScenarioV3UnloadTest         # client 3.3.9 × broker 3.3.9
mvn -Pclient-v4 test -Dtest=ScenarioV4UnloadTest         # client 4.2.0 × broker 4.2.0
```

Budget temps : ~9 min total pour les quatre scénarios A-D, dont 2×13 s de
restart broker pour A et B, et ~4 s d'unload pour D. Le scénario V3 ajoute
~40 s par run contre le broker 3.3.9 (client 2.11 ou 3.3).

## Comment 400 000 messages peuvent s'accumuler avec un seul déploiement hebdomadaire

La question honnête : en prod, le broker Pulsar n'est pas redémarré à chaque
déploiement — seul le consumer l'est. Alors d'où viennent les 400k ?

Une mise en prod hebdomadaire n'est pas le seul événement qui invalide les
compteurs en mémoire. En régime nominal, plusieurs mécanismes Pulsar les
invalident silencieusement, bien plus souvent qu'une fois par semaine :

### 1. Rebalancing des bundles de namespace (hypothèse la plus probable)

Pulsar découpe chaque namespace en *bundles* qui sont assignés aux brokers
par un load balancer (`loadBalancerEnabled=true` par défaut). Quand un
bundle migre d'un broker à l'autre :

- le cursor de la subscription est rechargé depuis BookKeeper sur le nouveau
  broker — la **position d'ack** est préservée ;
- mais l'`InMemoryRedeliveryTracker` associé au dispatcher est **purement en
  RAM** sur l'ancien broker et n'est pas sérialisé. Le tracker sur le
  nouveau broker redémarre à zéro.

La fréquence de rebalancing dépend de la charge et des seuils
(`loadBalancerSheddingIntervalMinutes=1` par défaut sur beaucoup de setups).
Dans un cluster chargé, un bundle peut migrer toutes les quelques minutes.

**Math** : pour que la DLQ soit **jamais** atteinte, il suffit que le temps
moyen entre deux invalidations du tracker soit inférieur à la durée d'un
cycle complet de backoff. Avec le backoff par défaut 1s → 60s × 2 et
`maxRedeliverCount=10`, la durée d'un cycle complet est :

```
1 + 2 + 4 + 8 + 16 + 32 + 60 + 60 + 60 + 60 = 303 s ≈ 5 min
```

Si les bundles migrent toutes les ~3-4 min, **aucun message n'atteint
jamais l'attempt 10**, et chaque poison reste indéfiniment dans le backlog.

### 2. Unload de bundles inactifs

Pulsar décharge les bundles avec peu d'activité (`loadBalancerAutoUnload`).
Même effet que le rebalancing : le tracker est perdu.

### 3. Rolling restart du broker pour scaling / patching

Dans un cluster k8s avec HPA ou des patches de sécurité, les pods broker
redémarrent plus souvent qu'on ne pense — typiquement une fois par jour à
une fois par semaine. Chaque restart vide l'`InMemoryRedeliveryTracker` de
tous les dispatchers du broker.

### 4. Rolling restart du consumer (le déploiement hebdo)

Même quand seul le consumer redémarre, il y a une fenêtre — souvent très
courte — où **zéro consumer n'est attaché** à la subscription. Pendant cette
fenêtre, le dispatcher du broker peut être fermé et son tracker perdu.
Au retour du consumer, le broker recrée un dispatcher *vierge*.

### Estimation

Avec les hypothèses ci-dessus combinées :

```
taux de poison ≈ backlog / temps d'observation
              ≈ 400_000 / 7 jours
              ≈ 0,66 msg/s
```

Ça n'est même pas spécialement élevé. Sur une application qui traite
quelques dizaines de messages par seconde et où 2 % échouent (validation,
champ manquant, endpoint downstream indisponible), on atteint facilement
0,66 poison/s. Les 400k deviennent **inévitables** dès que le cycle nack
est plus long que le temps moyen entre invalidations du tracker.

### Ce qu'il faut surveiller pour confirmer

- `pulsar_subscription_msg_rate_redeliver` sur la subscription incriminée :
  s'il est non-nul et stable, les poison tournent en boucle.
- `pulsar_subscription_back_log` : croissance linéaire = accumulation active.
- `pulsar_lb_unload_bundle_total` (compteur de bundles unloadés par le load
  balancer) : chaque incrément est un reset potentiel du tracker.
- Backlog du DLQ topic : si = 0 alors que le backlog principal est à 400k,
  la DLQ n'est jamais atteinte → on est exactement dans le Scénario A.

### Le fix reste le même

Peu importe laquelle des quatre causes domine : le correctif est
`reconsumeLater + enableRetry(true)`. Il immunise le compteur à tous ces
événements parce qu'il n'est plus stocké en RAM broker mais comme propriété
du message dans BookKeeper.

## Ce que le projet ne couvre pas

- Transactions Pulsar (2.8+) pour une sémantique exactly-once — B démontre
  l'at-least-once avec duplication possible en DLQ.
- Migration live d'une subscription existante qui utilise déjà `nack` vers
  `reconsumeLater` (il faut drainer le retry topic, recréer la subscription,
  ou utiliser un autre subscriptionName).
- Monitoring long terme (> 5 min) pour observer la stabilisation du backlog
  après DLQ effective. Scénario C pourrait être étendu.

## Structure du projet

```
bug-pulsar/
├── CLAUDE.md                       # mémo pour les futures sessions Claude Code
├── docker-compose.yml              # Pulsar 2.11.0 standalone
├── pom.xml                         # Java 17, pulsar-client 2.11.0, logback 1.2.12
├── docs/
│   ├── README.md                   # ← vous êtes ici
│   ├── scenario-a.md
│   ├── scenario-b.md
│   ├── scenario-c.md
│   ├── scenario-d.md
│   └── scenario-v3.md
└── src/
    ├── main/java/com/test/pulsar/
    │   ├── config/PulsarConfig.java        # shim back-compat vers PulsarEndpoint.V2
    │   ├── config/PulsarEndpoint.java      # record (container, serviceUrl, adminUrl) — constantes V2 / V3 / V4
    │   ├── config/TopicNames.java          # source unique du naming main/retry/dlq
    │   ├── consumer/NackConsumer.java      # expose getLastRedeliveryCountSeen() pour D / V3
    │   ├── consumer/ReconsumeLaterConsumer.java
    │   ├── producer/TestProducer.java
    │   └── util/PulsarMetrics.java         # stats via admin HTTP, overloads prenant un adminUrl
    ├── main/resources/
    │   └── logback.xml                     # console + FileAppender vers target/test.log
    └── test/java/com/test/pulsar/
        ├── AbstractPulsarScenarioTest.java # hook endpoint() + helpers dockerExecInBroker / getMsgInCounter / waitForBrokerReady
        ├── ScenarioATest.java
        ├── ScenarioBTest.java
        ├── ScenarioCTest.java
        ├── ScenarioDTest.java
        ├── ScenarioV3UnloadTest.java       # override endpoint() → V3, @EnabledIf skip si bug-pulsar-v3 down
        └── ScenarioV4UnloadTest.java       # override endpoint() → V4, @EnabledIf skip si bug-pulsar-v4 down
```

## Environnement

- Java 17+ (testé sur Java 21 Temurin)
- Maven 3.9+
- Docker + docker-compose
- Pulsar 2.11.0 obligatoire, Pulsar 3.3.9 et 4.2.0 optionnels — cf. [Versions testées](#versions-testées) ci-dessus pour le détail des images et de leur rôle
- Pas de dépendance native macOS (fallback DNS système, cf.
  [scenario-c.md](./scenario-c.md#pièges-rencontrés-pendant-lécriture-du-test))
