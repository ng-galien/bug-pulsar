package com.test.pulsar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.test.pulsar.consumer.NackConsumer;
import com.test.pulsar.util.PulsarMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scénario A — negativeAcknowledge sans enableRetry + restart simultané.
 *
 * Reproduit le bug de production : le redeliveryCount côté broker
 * (InMemoryRedeliveryTracker) ne survit pas à un restart simultané broker +
 * consumer. Résultat : la DLQ n'est jamais atteinte.
 */
class ScenarioATest extends AbstractPulsarScenarioTest {

    // Backoff multiplicatif : deliveries successives à t=0, 1, 3, 7, 15.
    // 9 s garantit d'avoir observé au moins la delivery t=7 (rc=3) même sous
    // démarrage client lent / Rosetta, sans allonger inutilement le test.
    private static final Duration INITIAL_NACK_CYCLES = Duration.ofSeconds(9);
    private static final Duration POST_RESTART_OBSERVATION = Duration.ofSeconds(3);
    private static final Duration DLQ_STAY_EMPTY_WINDOW = Duration.ofSeconds(30);
    private static final Duration BROKER_RESTART_TIMEOUT = Duration.ofSeconds(90);

    private NackConsumer consumer;

    @Override
    protected String topicPrefix() {
        return "test-nack-scenario";
    }

    @AfterEach
    void closeConsumer() {
        closeQuietly(consumer);
    }

    @Test
    void redeliveryCountResetsOnSimultaneousRestart() throws Exception {
        producer.publishPoisonMessages(10);
        consumer = new NackConsumer(client, names);

        Thread.sleep(INITIAL_NACK_CYCLES.toMillis());

        int countBeforeRestart = consumer.getMaxRedeliveryCountSeen();
        LOG.info("=== Avant restart : maxRedeliveryCountSeen={} messagesReceived={}",
            countBeforeRestart, consumer.getMessagesReceivedCount());
        assertTrue(countBeforeRestart >= 2,
            "Le redeliveryCount doit avoir monté à au moins 2 avant le restart (vu=" + countBeforeRestart + ")");

        long backlogBeforeRestart = PulsarMetrics.getBacklog(names.main(), names.subscription());
        LOG.info("=== Avant restart : backlog subscription={}", backlogBeforeRestart);

        // Restart simultané broker + consumer : NegativeAcksTracker local ET
        // InMemoryRedeliveryTracker broker sont tous les deux perdus.
        consumer.close();
        client.close();

        LOG.info("=== docker-compose restart pulsar");
        dockerComposeRestart("pulsar");
        waitForBrokerReady(BROKER_RESTART_TIMEOUT);
        LOG.info("=== broker prêt après restart");

        client = com.test.pulsar.config.PulsarConfig.newClient();
        consumer = new NackConsumer(client, names);

        Thread.sleep(POST_RESTART_OBSERVATION.toMillis());

        int countAfterRestart = consumer.getMaxRedeliveryCountSeen();
        LOG.info("=== Après restart : maxRedeliveryCountSeen={} messagesReceived={}",
            countAfterRestart, consumer.getMessagesReceivedCount());
        assertTrue(countAfterRestart <= Math.max(1, countBeforeRestart - 1),
            "Après restart, le redeliveryCount observé devrait être inférieur à celui d'avant "
                + "(avant=" + countBeforeRestart + " après=" + countAfterRestart + ")");

        Thread.sleep(DLQ_STAY_EMPTY_WINDOW.toMillis());
        long dlqMsgIn = PulsarMetrics.getMsgInCounter(names.dlq());
        LOG.info("=== Après {}s supplémentaires : dlqMsgInCounter={}",
            DLQ_STAY_EMPTY_WINDOW.toSeconds(), dlqMsgIn);
        assertEquals(0, dlqMsgIn,
            "La DLQ doit rester vide : le compteur se remet à zéro à chaque restart "
                + "et le seuil maxRedeliverCount n'est jamais atteint");
    }
}
