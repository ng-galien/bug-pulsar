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
 * Scénario V4 — le bug persiste-t-il sur Pulsar 4.2.0 ?
 *
 * Rejoue exactement le Scénario D (topic unload sans restart broker) contre un
 * conteneur standalone {@code bug-pulsar-v4} qui tourne Pulsar 4.2.0.
 *
 * Prérequis :
 *   docker-compose --profile v4 up -d pulsar-v4
 */
@EnabledIf("isV4BrokerReady")
class ScenarioV4UnloadTest extends AbstractPulsarScenarioTest {

    @SuppressWarnings("unused") // referenced by @EnabledIf
    static boolean isV4BrokerReady() {
        return PulsarMetrics.isBrokerReady(PulsarEndpoint.V4.adminUrl());
    }

    private static final Duration INITIAL_NACK_CYCLES = Duration.ofSeconds(9);
    private static final Duration POST_UNLOAD_OBSERVATION = Duration.ofSeconds(5);
    private static final Duration DLQ_STAY_EMPTY_WINDOW = Duration.ofSeconds(20);

    private NackConsumer consumer;

    @Override
    protected PulsarEndpoint endpoint() {
        return PulsarEndpoint.V4;
    }

    @Override
    protected String topicPrefix() {
        return "test-nack-unload-v4";
    }

    @AfterEach
    void closeConsumer() {
        closeQuietly(consumer);
    }

    @Test
    void topicUnloadResetsRedeliveryTrackerOnPulsar4() throws Exception {
        producer.publishPoisonMessages(10);
        consumer = new NackConsumer(client, names);

        Thread.sleep(INITIAL_NACK_CYCLES.toMillis());

        int countBeforeUnload = consumer.getMaxRedeliveryCountSeen();
        LOG.info("=== [V4] Avant unload : maxRedeliveryCountSeen={} messagesReceived={}",
            countBeforeUnload, consumer.getMessagesReceivedCount());
        assertTrue(countBeforeUnload >= 2,
            "Le redeliveryCount doit avoir monté à au moins 2 avant l'unload (vu=" + countBeforeUnload + ")");

        int receivedBeforeUnload = consumer.getMessagesReceivedCount();

        LOG.info("=== [V4] pulsar-admin topics unload {}", names.main());
        dockerExecInBroker("bin/pulsar-admin", "topics", "unload", names.main());
        LOG.info("=== [V4] topic unloaded");

        Thread.sleep(POST_UNLOAD_OBSERVATION.toMillis());

        int lastRcAfterUnload = consumer.getLastRedeliveryCountSeen();
        int observedSinceUnload = consumer.getMessagesReceivedCount() - receivedBeforeUnload;
        LOG.info("=== [V4] Après unload : lastRedeliveryCountSeen={} messagesReceivedSinceUnload={}",
            lastRcAfterUnload, observedSinceUnload);

        assertTrue(observedSinceUnload > 0,
            "Le consumer aurait dû se réattacher et recevoir des redeliveries après l'unload "
                + "(vu=" + observedSinceUnload + ")");

        assertTrue(lastRcAfterUnload < countBeforeUnload,
            "[V4] Le dernier redeliveryCount après unload (" + lastRcAfterUnload
                + ") devrait être strictement inférieur au max pré-unload ("
                + countBeforeUnload + ") — si cette assertion échoue, Pulsar 4.2.0 "
                + "a peut-être corrigé le bug et il faut vérifier l'upstream");

        Thread.sleep(DLQ_STAY_EMPTY_WINDOW.toMillis());
        long dlqMsgIn = getMsgInCounter(names.dlq());
        LOG.info("=== [V4] Après {}s supplémentaires : dlqMsgInCounter={}",
            DLQ_STAY_EMPTY_WINDOW.toSeconds(), dlqMsgIn);
        assertTrue(dlqMsgIn == 0,
            "[V4] La DLQ doit rester vide — le seuil maxRedeliverCount n'est jamais "
                + "atteint dans la fenêtre du test (vu=" + dlqMsgIn + ")");
    }
}
