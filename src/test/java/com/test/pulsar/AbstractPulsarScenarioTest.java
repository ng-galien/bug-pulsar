package com.test.pulsar;

import com.test.pulsar.config.PulsarEndpoint;
import com.test.pulsar.config.TopicNames;
import com.test.pulsar.producer.TestProducer;
import com.test.pulsar.util.PulsarMetrics;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
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

    /**
     * Override to target a different broker (e.g. V3). Defaults to the 2.11.0
     * container used by scenarios A through D.
     */
    protected PulsarEndpoint endpoint() {
        return PulsarEndpoint.V2;
    }

    @BeforeEach
    final void baseSetUp() throws Exception {
        names = TopicNames.forTest(topicPrefix());
        client = endpoint().newClient();
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

    /**
     * Run a command inside a Pulsar container via {@code docker exec}. Drains
     * stdout/stderr the same way {@link #dockerComposeRestart(String)} does so
     * surefire's reporter channel stays clean. Throws if the command exits non-zero.
     */
    protected static void dockerExec(String container, String... cmd) throws IOException, InterruptedException {
        List<String> full = new ArrayList<>();
        full.add("docker");
        full.add("exec");
        full.add(container);
        for (String c : cmd) full.add(c);
        ProcessBuilder pb = new ProcessBuilder(full).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                LOG.info("[docker exec] {}", line);
            }
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("docker exec " + String.join(" ", full) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("docker exec " + String.join(" ", full) + " exited " + p.exitValue());
        }
    }

    /** Convenience: run a command against the current test's broker container. */
    protected void dockerExecInBroker(String... cmd) throws IOException, InterruptedException {
        dockerExec(endpoint().container(), cmd);
    }

    /** Backlog of a subscription on the current broker. */
    protected long getBacklog(String topic, String subscription) throws Exception {
        return PulsarMetrics.getBacklog(endpoint().adminUrl(), topic, subscription);
    }

    /** Cumulative msgInCounter for a topic on the current broker. */
    protected long getMsgInCounter(String topic) throws Exception {
        return PulsarMetrics.getMsgInCounter(endpoint().adminUrl(), topic);
    }

    protected void waitForBrokerReady(Duration timeout) {
        String adminUrl = endpoint().adminUrl();
        Awaitility.await()
            .atMost(timeout)
            .pollInterval(Duration.ofSeconds(1))
            .until(() -> PulsarMetrics.isBrokerReady(adminUrl));
    }
}
