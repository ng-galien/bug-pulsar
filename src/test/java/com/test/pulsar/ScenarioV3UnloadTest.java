package com.test.pulsar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.test.pulsar.config.PulsarEndpoint;
import com.test.pulsar.consumer.NackConsumer;
import com.test.pulsar.util.PulsarMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Scénario V3 — le bug persiste-t-il sur Pulsar 3.3.9 ?
 *
 * Rejoue exactement le Scénario D (topic unload sans restart broker) contre un
 * second conteneur standalone {@code bug-pulsar-v3} qui tourne Pulsar 3.3.9.
 * Si l'assertion {@code lastRcAfterUnload < countBeforeUnload} passe, l'upstream
 * n'a pas corrigé le mécanisme — l'{@code InMemoryRedeliveryTracker} est
 * toujours dispatcher-scoped et toujours perdu au unload — et notre correctif
 * {@code reconsumeLater + enableRetry(true)} reste nécessaire en Pulsar 3.
 *
 * Le client Pulsar reste en 2.11.0 : broker et client sont wire-compatibles,
 * et on veut isoler la question "est-ce que le broker 3.x gère différemment
 * l'état du tracker ?" indépendamment de toute évolution du client.
 *
 * Prérequis pour l'exécuter :
 *   docker-compose --profile v3 up -d pulsar-v3
 *
 * Le service V3 vit dans le profile {@code v3} pour ne pas démarrer en même
 * temps que le broker V2 lors d'un {@code docker-compose up -d} classique.
 */
@EnabledIf("isV3BrokerReady")
class ScenarioV3UnloadTest extends AbstractPulsarScenarioTest {

    /**
     * Skip the whole class if the V3 broker isn't running. Avoids a hard
     * connection-refused failure in baseSetUp when someone runs {@code mvn test}
     * without having started {@code pulsar-v3}.
     */
    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean isV3BrokerReady() {
        return PulsarMetrics.isBrokerReady(PulsarEndpoint.V3.adminUrl());
    }


    private static final Duration INITIAL_NACK_CYCLES = Duration.ofSeconds(9);
    private static final Duration POST_UNLOAD_OBSERVATION = Duration.ofSeconds(5);
    private static final Duration DLQ_STAY_EMPTY_WINDOW = Duration.ofSeconds(20);

    private NackConsumer consumer;

    @Override
    protected PulsarEndpoint endpoint() {
        return PulsarEndpoint.V3;
    }

    @Override
    protected String topicPrefix() {
        return "test-nack-unload-v3";
    }

    @AfterEach
    void closeConsumer() {
        closeQuietly(consumer);
    }

    @Test
    void topicUnloadResetsRedeliveryTrackerOnPulsar3() throws Exception {
        producer.publishPoisonMessages(10);
        consumer = new NackConsumer(client, names);

        Thread.sleep(INITIAL_NACK_CYCLES.toMillis());

        int countBeforeUnload = consumer.getMaxRedeliveryCountSeen();
        LOG.info("=== [V3] Avant unload : maxRedeliveryCountSeen={} messagesReceived={}",
            countBeforeUnload, consumer.getMessagesReceivedCount());
        assertTrue(countBeforeUnload >= 2,
            "Le redeliveryCount doit avoir monté à au moins 2 avant l'unload (vu=" + countBeforeUnload + ")");

        int receivedBeforeUnload = consumer.getMessagesReceivedCount();

        LOG.info("=== [V3] pulsar-admin topics unload {}", names.main());
        dockerExecInBroker("bin/pulsar-admin", "topics", "unload", names.main());
        LOG.info("=== [V3] topic unloaded");

        Thread.sleep(POST_UNLOAD_OBSERVATION.toMillis());

        int lastRcAfterUnload = consumer.getLastRedeliveryCountSeen();
        int observedSinceUnload = consumer.getMessagesReceivedCount() - receivedBeforeUnload;
        LOG.info("=== [V3] Après unload : lastRedeliveryCountSeen={} messagesReceivedSinceUnload={}",
            lastRcAfterUnload, observedSinceUnload);

        assertTrue(observedSinceUnload > 0,
            "Le consumer aurait dû se réattacher et recevoir des redeliveries après l'unload "
                + "(vu=" + observedSinceUnload + ")");

        // Si cette assertion PASSE → Pulsar 3 a le même bug que 2.11 : le tracker
        // est toujours en RAM broker et se perd au unload.
        // Si cette assertion ÉCHOUE (le rc reste au moins à countBeforeUnload)
        // → l'upstream a persisté le tracker côté broker ou a rendu le reset
        // invisible côté client. Dans ce cas, relire JIRA/issue tracker Pulsar.
        assertTrue(lastRcAfterUnload < countBeforeUnload,
            "[V3] Le dernier redeliveryCount après unload (" + lastRcAfterUnload
                + ") devrait être strictement inférieur au max pré-unload ("
                + countBeforeUnload + ") — si cette assertion échoue, Pulsar 3.3.9 "
                + "a peut-être corrigé le bug et il faut vérifier l'upstream");

        Thread.sleep(DLQ_STAY_EMPTY_WINDOW.toMillis());
        long dlqMsgIn = getMsgInCounter(names.dlq());
        LOG.info("=== [V3] Après {}s supplémentaires : dlqMsgInCounter={}",
            DLQ_STAY_EMPTY_WINDOW.toSeconds(), dlqMsgIn);
        assertTrue(dlqMsgIn == 0,
            "[V3] La DLQ doit rester vide — le seuil maxRedeliverCount n'est jamais "
                + "atteint dans la fenêtre du test (vu=" + dlqMsgIn + ")");
    }
}
