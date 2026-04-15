# bug-pulsar — reproduction du flood de DLQ manquante

Projet de test qui reproduit et quantifie un bug Pulsar observé en production :
un backlog de ~400 000 messages qui ne passe jamais en DLQ, à cause d'une
interaction entre `negativeAcknowledge` et le restart simultané du broker et
du consumer.

## TL;DR

- **Bug** : `negativeAcknowledge` *sans* `enableRetry(true)` stocke le
  compteur de redelivery uniquement en RAM (client + broker). Un restart
  simultané le perd, la DLQ n'est jamais atteinte, le backlog gonfle.
- **Fix** : passer à `reconsumeLater(...)` avec `enableRetry(true)`. Le
  compteur `RECONSUMETIMES` est persisté comme propriété du message dans
  BookKeeper et survit à n'importe quel restart.
- **Preuves mesurées** : trois scénarios de test isolent chaque aspect du
  bug contre un broker Pulsar 2.11.0 en standalone.

## Tableau récapitulatif

| | Scénario A — nack | Scénario B — reconsumeLater | Scénario C — accumulation |
|---|---|---|---|
| **Stratégie testée** | `negativeAcknowledge` sans `enableRetry` | `reconsumeLater` + `enableRetry(true)` | comparaison nack vs reconsumeLater sous flux continu |
| **Durée du test** | ~60 s | ~30 s | ~3 min 30 (2 × 90 s) |
| **Restart broker** | ✅ via `docker-compose restart` | ✅ via `docker-compose restart` | ❌ régime nominal |
| **Compteur survit au restart** | ❌ remis à 0 | ✅ `RECONSUMETIMES` intact | n/a |
| **DLQ atteinte** | ❌ jamais (0 messages) | ✅ 16 messages (5 publiés, duplication at-least-once) | n/a |
| **Backlog après 90 s** | n/a | n/a | nack **452** vs reconsumeLater **0** |
| **Assertion clef** | `dlqMsgInCounter == 0` après restart | `dlqMsgInCounter >= 5` après restart | nack croît linéairement, reconsumeLater reste plat |
| **Documentation** | [scenario-a.md](./scenario-a.md) | [scenario-b.md](./scenario-b.md) | [scenario-c.md](./scenario-c.md) |

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
mvn test                      # lance A + B + C
# ou bien ciblé :
mvn test -Dtest=ScenarioATest
```

Budget temps : ~8 min total pour les trois scénarios, dont 2×13 s de restart
broker pour A et B.

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
├── docker-compose.yml              # Pulsar 2.11.0 standalone
├── pom.xml                         # Java 17, pulsar-client 2.11.0, logback 1.2.12
├── docs/
│   ├── README.md                   # ← vous êtes ici
│   ├── scenario-a.md
│   ├── scenario-b.md
│   └── scenario-c.md
└── src/
    ├── main/java/com/test/pulsar/
    │   ├── config/PulsarConfig.java
    │   ├── consumer/NackConsumer.java
    │   ├── consumer/ReconsumeLaterConsumer.java
    │   ├── producer/TestProducer.java
    │   └── util/PulsarMetrics.java
    ├── main/resources/
    │   └── logback.xml             # console + FileAppender vers target/test.log
    └── test/java/com/test/pulsar/
        ├── ScenarioATest.java
        ├── ScenarioBTest.java
        └── ScenarioCTest.java
```

## Environnement

- Java 17+ (testé sur Java 21 Temurin)
- Maven 3.9+
- Docker + docker-compose
- Pulsar 2.11.0 (image officielle `apachepulsar/pulsar:2.11.0`)
- Pas de dépendance native macOS (fallback DNS système, cf.
  [scenario-c.md](./scenario-c.md#pièges-rencontrés-pendant-lécriture-du-test))
