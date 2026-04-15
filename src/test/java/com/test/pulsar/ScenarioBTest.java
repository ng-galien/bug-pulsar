package com.test.pulsar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.test.pulsar.config.PulsarConfig;
import com.test.pulsar.consumer.ReconsumeLaterConsumer;
import com.test.pulsar.util.PulsarMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scénario B — reconsumeLater avec enableRetry(true) + restart simultané.
 *
 * Démontre qu'avec enableRetry(true), RECONSUMETIMES est stocké comme
 * propriété du message sur le retry topic (donc persisté dans BookKeeper).
 * Un restart simultané broker + consumer ne perd pas cet état, et la DLQ
 * est bien atteinte après maxRedeliverCount tentatives.
 */
class ScenarioBTest extends AbstractPulsarScenarioTest {

    private static final int POISON_COUNT = 5;

    // ~2-3 cycles de reconsumeLater avant restart.
    private static final Duration INITIAL_RECONSUME_CYCLES =
        Duration.ofSeconds(ReconsumeLaterConsumer.RECONSUME_DELAY_SECONDS * 3);

    // Laps de temps post-restart suffisant pour vider le retry topic vers DLQ.
    // = (maxRedeliverCount tentatives restantes) * délai + marge large.
    private static final Duration POST_RESTART_DRAIN_WINDOW = Duration.ofSeconds(
        ReconsumeLaterConsumer.MAX_REDELIVER_COUNT * ReconsumeLaterConsumer.RECONSUME_DELAY_SECONDS + 9);

    private static final Duration BROKER_RESTART_TIMEOUT = Duration.ofSeconds(90);

    private ReconsumeLaterConsumer consumer;

    @Override
    protected String topicPrefix() {
        return "test-reconsumelater-scenario";
    }

    @AfterEach
    void closeConsumer() {
        closeQuietly(consumer);
    }

    @Test
    void reconsumeLaterSurvivesSimultaneousRestart() throws Exception {
        producer.publishPoisonMessages(POISON_COUNT);
        consumer = new ReconsumeLaterConsumer(client, names);

        Thread.sleep(INITIAL_RECONSUME_CYCLES.toMillis());

        int reconsumeBefore = consumer.getMaxReconsumeTimesSeen();
        int recvBefore = consumer.getMessagesReceivedCount();
        LOG.info("=== Avant restart : maxReconsumeTimesSeen={} messagesReceived={}",
            reconsumeBefore, recvBefore);
        assertTrue(reconsumeBefore >= 1,
            "RECONSUMETIMES doit avoir monté à >= 1 avant le restart (vu=" + reconsumeBefore + ")");

        long retryMsgIn = PulsarMetrics.getMsgInCounter(names.retry());
        LOG.info("=== Avant restart : retry topic msgInCounter={}", retryMsgIn);
        assertTrue(retryMsgIn >= POISON_COUNT,
            "Le retry topic doit avoir reçu au moins " + POISON_COUNT
                + " messages (vu=" + retryMsgIn + ")");

        consumer.close();
        client.close();

        LOG.info("=== docker-compose restart pulsar");
        dockerComposeRestart("pulsar");
        waitForBrokerReady(BROKER_RESTART_TIMEOUT);
        LOG.info("=== broker prêt après restart");

        client = PulsarConfig.newClient();
        consumer = new ReconsumeLaterConsumer(client, names);

        Thread.sleep(POST_RESTART_DRAIN_WINDOW.toMillis());

        int reconsumeAfter = consumer.getMaxReconsumeTimesSeen();
        int recvAfter = consumer.getMessagesReceivedCount();
        LOG.info("=== Après restart : maxReconsumeTimesSeen={} messagesReceived={}",
            reconsumeAfter, recvAfter);

        // RECONSUMETIMES a bien été préservé dans BookKeeper pendant le restart.
        assertTrue(reconsumeAfter >= reconsumeBefore,
            "RECONSUMETIMES doit avoir été préservé (avant=" + reconsumeBefore
                + " après=" + reconsumeAfter + ")");

        long dlqMsgIn = PulsarMetrics.getMsgInCounter(names.dlq());
        LOG.info("=== Après restart : dlqMsgInCounter={}", dlqMsgIn);
        assertTrue(dlqMsgIn >= POISON_COUNT,
            "La DLQ doit contenir au moins " + POISON_COUNT
                + " messages après " + ReconsumeLaterConsumer.MAX_REDELIVER_COUNT
                + " tentatives (vu=" + dlqMsgIn + ")");
    }
}
