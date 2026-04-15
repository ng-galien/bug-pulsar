package com.test.pulsar.config;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

/**
 * Back-compat shim over {@link PulsarEndpoint#V2}. New code should prefer
 * {@code PulsarEndpoint.V2.newClient()} directly and pass an endpoint explicitly
 * so multi-version tests (V2 vs V3) can share the same plumbing.
 */
public final class PulsarConfig {

    public static final String SERVICE_URL = PulsarEndpoint.V2.serviceUrl();
    public static final String ADMIN_URL = PulsarEndpoint.V2.adminUrl();

    private PulsarConfig() {}

    public static PulsarClient newClient() throws PulsarClientException {
        return PulsarEndpoint.V2.newClient();
    }
}
