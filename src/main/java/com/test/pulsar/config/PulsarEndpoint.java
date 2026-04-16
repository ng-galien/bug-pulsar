package com.test.pulsar.config;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

/**
 * Broker coordinates: the Pulsar binary/HTTP endpoints plus the container name
 * used by tests that shell out via {@code docker exec}. Each broker version we
 * test against gets its own constant so a single {@code docker-compose} file can
 * host several Pulsar versions side by side.
 */
public record PulsarEndpoint(String container, String serviceUrl, String adminUrl) {

    public static final PulsarEndpoint V2 =
        new PulsarEndpoint("bug-pulsar", "pulsar://localhost:6650", "http://localhost:8080");

    public static final PulsarEndpoint V3 =
        new PulsarEndpoint("bug-pulsar-v3", "pulsar://localhost:6651", "http://localhost:8081");

    public static final PulsarEndpoint V4 =
        new PulsarEndpoint("bug-pulsar-v4", "pulsar://localhost:6652", "http://localhost:8082");

    public PulsarClient newClient() throws PulsarClientException {
        return PulsarClient.builder()
            .serviceUrl(serviceUrl)
            .build();
    }
}
