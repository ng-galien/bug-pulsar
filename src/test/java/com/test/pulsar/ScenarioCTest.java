package com.test.pulsar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.test.pulsar.consumer.NackConsumer;
import com.test.pulsar.consumer.ReconsumeLaterConsumer;
import com.test.pulsar.util.PulsarMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.pulsar.client.api.Message;
import org.junit.jupiter.api.Test;

/**
 * Scénario C — accumulation continue du backlog.
 *
 * Compare nack vs reconsumeLater sous un flux continu (10 msg/s, 50/50
 * good/poison). Avec nack le backlog de la subscription principale croît
 * linéairement ; avec reconsumeLater il reste proche de zéro car les
 * messages poison sont immédiatement acked et republiés sur le retry topic.
 */
class ScenarioCTest extends AbstractPulsarScenarioTest {

    private static final int MESSAGES_PER_SECOND = 10;
    private static final Duration TEST_DURATION = Duration.ofSeconds(90);
    private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(10);
    private static final Duration PUBLISHER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

    @Override
    protected String topicPrefix() {
        return "test-scenario-c";
    }

    @Test
    void backlogGrowsUnboundedWithNack() throws Exception {
        try (NackConsumer consumer = new NackConsumer(client, names, ScenarioCTest::isGoodMessage)) {
            List<Long> samples = runAndSample(TEST_DURATION, SAMPLE_INTERVAL);

            LOG.info("=== Nack run — backlog samples: {}", samples);
            LOG.info("=== Nack run — acked={} nacked={} received={}",
                consumer.getMessagesAckedCount(),
                consumer.getMessagesNackedCount(),
                consumer.getMessagesReceivedCount());

            long first = samples.get(0);
            long last = samples.get(samples.size() - 1);
            assertTrue(last > 100,
                "Nack: backlog final doit être > 100 (observé=" + last + ")");
            assertTrue(last > first * 2,
                "Nack: backlog doit avoir au moins doublé (first=" + first + " last=" + last + ")");
        }
    }

    @Test
    void backlogStabilizesWithReconsumeLater() throws Exception {
        try (ReconsumeLaterConsumer consumer =
                 new ReconsumeLaterConsumer(client, names, ScenarioCTest::isGoodMessage)) {
            List<Long> samples = runAndSample(TEST_DURATION, SAMPLE_INTERVAL);

            LOG.info("=== ReconsumeLater run — backlog samples: {}", samples);
            LOG.info("=== ReconsumeLater run — acked={} reconsumed={} received={}",
                consumer.getMessagesAckedCount(),
                consumer.getMessagesReconsumedCount(),
                consumer.getMessagesReceivedCount());

            long last = samples.get(samples.size() - 1);
            long max = samples.stream().mapToLong(Long::longValue).max().orElse(0);
            assertTrue(max < 100,
                "ReconsumeLater: backlog max doit rester < 100 (observé=" + max + ")");
            assertTrue(last < 100,
                "ReconsumeLater: backlog final doit rester < 100 (observé=" + last + ")");
        }
    }

    private List<Long> runAndSample(Duration total, Duration interval) throws Exception {
        ExecutorService publisherExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "scenario-c-publisher");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger publishedCount = new AtomicInteger(0);
        AtomicReference<Throwable> publisherError = new AtomicReference<>();

        publisherExec.submit(() -> {
            long intervalNs = 1_000_000_000L / MESSAGES_PER_SECOND;
            long next = System.nanoTime();
            int i = 0;
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    String payload = (i % 2 == 0 ? "good-" : "poison-") + i;
                    producer.send(payload);
                    publishedCount.incrementAndGet();
                    i++;
                } catch (Exception e) {
                    if (!stop.get()) {
                        publisherError.set(e);
                    }
                    return;
                }
                next += intervalNs;
                long sleepNs = next - System.nanoTime();
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });

        List<Long> samples = new ArrayList<>();
        long startNs = System.nanoTime();
        long deadlineNs = startNs + total.toNanos();
        while (System.nanoTime() < deadlineNs) {
            Thread.sleep(interval.toMillis());
            Throwable err = publisherError.get();
            if (err != null) {
                throw new IllegalStateException("publisher thread failed", err);
            }
            long backlog = PulsarMetrics.getBacklog(names.main(), names.subscription());
            long elapsed = (System.nanoTime() - startNs) / 1_000_000_000L;
            LOG.info("t={}s published={} backlog={}", elapsed, publishedCount.get(), backlog);
            samples.add(backlog);
        }

        // shutdownNow interrupts the blocked Thread.sleep in the publisher and
        // races any in-flight producer.send to completion.
        stop.set(true);
        publisherExec.shutdownNow();
        if (!publisherExec.awaitTermination(PUBLISHER_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            LOG.warn("publisher thread did not terminate within {}", PUBLISHER_SHUTDOWN_TIMEOUT);
        }
        return samples;
    }

    private static boolean isGoodMessage(Message<String> msg) {
        return !new String(msg.getData()).startsWith("poison-");
    }
}
