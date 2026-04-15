package com.test.pulsar.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.test.pulsar.config.PulsarConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PulsarMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(PulsarMetrics.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PulsarMetrics() {}

    // ---- Back-compat no-arg variants — target the default V2 admin URL. ----

    public static long getBacklog(String topic, String subscription) throws Exception {
        return getBacklog(PulsarConfig.ADMIN_URL, topic, subscription);
    }

    public static long getMsgInCounter(String topic) throws Exception {
        return getMsgInCounter(PulsarConfig.ADMIN_URL, topic);
    }

    public static long getStorageSize(String topic) throws Exception {
        return getStorageSize(PulsarConfig.ADMIN_URL, topic);
    }

    public static boolean isBrokerReady() {
        return isBrokerReady(PulsarConfig.ADMIN_URL);
    }

    // ---- Admin-URL-aware variants — usable across multiple broker versions. ----

    /** Backlog of a specific subscription on a topic. Returns 0 if topic/subscription not found. */
    public static long getBacklog(String adminUrl, String topic, String subscription) throws Exception {
        JsonNode stats = fetchStats(adminUrl, topic);
        if (stats == null) return 0;
        JsonNode subs = stats.get("subscriptions");
        if (subs == null) return 0;
        JsonNode sub = subs.get(subscription);
        if (sub == null) return 0;
        JsonNode backlog = sub.get("msgBacklog");
        return backlog != null ? backlog.asLong() : 0;
    }

    /** Total messages ever published to a topic (cumulative counter). Used to detect DLQ arrivals. */
    public static long getMsgInCounter(String adminUrl, String topic) throws Exception {
        JsonNode stats = fetchStats(adminUrl, topic);
        if (stats == null) return 0;
        JsonNode counter = stats.get("msgInCounter");
        return counter != null ? counter.asLong() : 0;
    }

    /** Storage size in bytes. */
    public static long getStorageSize(String adminUrl, String topic) throws Exception {
        JsonNode stats = fetchStats(adminUrl, topic);
        if (stats == null) return 0;
        JsonNode size = stats.get("storageSize");
        return size != null ? size.asLong() : 0;
    }

    /** Quick ping of the broker admin API. Returns true if broker is serving HTTP. */
    public static boolean isBrokerReady(String adminUrl) {
        try {
            URI uri = URI.create(adminUrl + "/admin/v2/brokers/health");
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Block until {@link #isBrokerReady(String)} returns true or the deadline passes. */
    public static void waitForBrokerReady(String adminUrl, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (isBrokerReady(adminUrl)) return;
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("Broker not ready after " + timeout);
    }

    /** Back-compat: same as {@link #waitForBrokerReady(String, Duration)} against the default V2 URL. */
    public static void waitForBrokerReady(Duration timeout) throws InterruptedException {
        waitForBrokerReady(PulsarConfig.ADMIN_URL, timeout);
    }

    private static JsonNode fetchStats(String adminUrl, String topic) throws Exception {
        String shortName = topic.replace("persistent://", "");
        URI uri = URI.create(adminUrl + "/admin/v2/persistent/" + shortName + "/stats");
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            // Topic does not exist yet (e.g. DLQ not created)
            return null;
        }
        if (resp.statusCode() != 200) {
            LOG.warn("Stats call failed for {} -> HTTP {} body={}", topic, resp.statusCode(), resp.body());
            return null;
        }
        return MAPPER.readTree(resp.body());
    }
}
