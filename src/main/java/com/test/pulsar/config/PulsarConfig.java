package com.test.pulsar.config;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

public final class PulsarConfig {

    public static final String SERVICE_URL = "pulsar://localhost:6650";
    public static final String ADMIN_URL = "http://localhost:8080";

    private PulsarConfig() {}

    public static PulsarClient newClient() throws PulsarClientException {
        return PulsarClient.builder()
            .serviceUrl(SERVICE_URL)
            .build();
    }
}
