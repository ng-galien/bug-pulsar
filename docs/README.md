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
- **Preuves mesurées** : quatre scénarios de test isolent chaque aspect du
  bug contre un broker Pulsar 2.11.0 en standalone.

## Tableau récapitulatif

| | Scénario A — nack + restart | Scénario B — reconsumeLater + restart | Scénario C — accumulation | Scénario D — unload sans restart |
|---|---|---|---|---|
| **Stratégie testée** | `negativeAcknowledge` sans `enableRetry` | `reconsumeLater` + `enableRetry(true)` | nack vs reconsumeLater sous flux continu | `negativeAcknowledge` sans `enableRetry` |
| **Durée du test** | ~60 s | ~30 s | ~3 min 30 (2 × 90 s) | ~40 s |
| **Événement appliqué** | `docker-compose restart pulsar` | `docker-compose restart pulsar` | aucun (régime nominal) | `pulsar-admin topics unload` (broker up) |
| **Compteur survit à l'événement** | ❌ remis à 0 | ✅ `RECONSUMETIMES` intact | n/a | ❌ remis à 0 |
| **DLQ atteinte** | ❌ jamais (0 messages) | ✅ 16 messages (5 publiés, duplication at-least-once) | n/a | ❌ jamais (0 messages) |
| **Backlog après 90 s** | n/a | n/a | nack **452** vs reconsumeLater **0** | n/a |
| **Assertion clef** | `countAfterRestart < countBeforeRestart` & `dlqMsgInCounter == 0` | `dlqMsgInCounter >= 5` après restart | nack croît linéairement, reconsumeLater reste plat | `lastRedeliveryCountSeen < maxBeforeUnload` & `dlqMsgInCounter == 0` |
| **Documentation** | [scenario-a.md](./scenario-a.md) | [scenario-b.md](./scenario-b.md) | [scenario-c.md](./scenario-c.md) | [scenario-d.md](./scenario-d.md) |

**Le rôle du scénario D** : prouver que le bug ne dépend pas d'un restart
broker. Un simple topic unload (équivalent d'un bundle rebalancing en prod)
suffit à perdre le tracker et empêche la DLQ d'être atteinte. C'est le
mécanisme qui produit les 400k en régime nominal, pas les déploiements
hebdomadaires. Voir [scenario-d.md](./scenario-d.md) pour la démonstration.

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
mvn test                      # lance A + B + C + D
# ou bien ciblé :
mvn test -Dtest=ScenarioATest
```

Budget temps : ~9 min total pour les quatre scénarios, dont 2×13 s de restart
broker pour A et B, et ~4 s d'unload pour D.

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
- `pulsar_topic_load_balancer_bundles_split_count` et les événements
  `bundleUnloading` : chaque événement est un reset potentiel du tracker.
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
│   └── scenario-d.md
└── src/
    ├── main/java/com/test/pulsar/
    │   ├── config/PulsarConfig.java
    │   ├── config/TopicNames.java          # source unique du naming main/retry/dlq
    │   ├── consumer/NackConsumer.java      # expose getLastRedeliveryCountSeen() pour D
    │   ├── consumer/ReconsumeLaterConsumer.java
    │   ├── producer/TestProducer.java
    │   └── util/PulsarMetrics.java         # stats via admin HTTP + isBrokerReady
    ├── main/resources/
    │   └── logback.xml                     # console + FileAppender vers target/test.log
    └── test/java/com/test/pulsar/
        ├── AbstractPulsarScenarioTest.java # setUp/tearDown + dockerComposeRestart + dockerExec + waitForBrokerReady
        ├── ScenarioATest.java
        ├── ScenarioBTest.java
        ├── ScenarioCTest.java
        └── ScenarioDTest.java
```

## Environnement

- Java 17+ (testé sur Java 21 Temurin)
- Maven 3.9+
- Docker + docker-compose
- Pulsar 2.11.0 (image officielle `apachepulsar/pulsar:2.11.0`)
- Pas de dépendance native macOS (fallback DNS système, cf.
  [scenario-c.md](./scenario-c.md#pièges-rencontrés-pendant-lécriture-du-test))
