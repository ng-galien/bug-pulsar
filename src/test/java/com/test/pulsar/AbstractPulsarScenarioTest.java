package com.test.pulsar;

import com.test.pulsar.config.PulsarConfig;
import com.test.pulsar.config.TopicNames;
import com.test.pulsar.producer.TestProducer;
import com.test.pulsar.util.PulsarMetrics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.PulsarClient;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class AbstractPulsarScenarioTest {

    protected static final Logger LOG = LoggerFactory.getLogger(AbstractPulsarScenarioTest.class);

    protected TopicNames names;
    protected PulsarClient client;
    protected TestProducer producer;

    protected abstract String topicPrefix();

    @BeforeEach
    final void baseSetUp() throws Exception {
        names = TopicNames.forTest(topicPrefix());
        client = PulsarConfig.newClient();
        producer = new TestProducer(client, names.main());
    }

    @AfterEach
    final void baseTearDown() {
        closeQuietly(producer);
        closeQuietly(client);
    }

    protected static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }

    protected static void dockerComposeRestart(String service) throws IOException, InterruptedException {
        // Never inheritIO() here: surefire's forked JVM uses its native stdout
        // for the reporter protocol, and raw bytes from a child corrupt it.
        ProcessBuilder pb = new ProcessBuilder(List.of("docker-compose", "restart", service))
            .redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                LOG.info("[docker-compose] {}", line);
            }
        }
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker-compose restart " + service + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("docker-compose restart " + service + " exited " + p.exitValue());
        }
    }

    protected static void waitForBrokerReady(Duration timeout) {
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofSeconds(1))
            .until(PulsarMetrics::isBrokerReady);
    }
}
