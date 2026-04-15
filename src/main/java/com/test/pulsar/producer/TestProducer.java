package com.test.pulsar.producer;

import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestProducer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TestProducer.class);

    private final Producer<String> producer;

    public TestProducer(PulsarClient client, String topic) throws PulsarClientException {
        this.producer = client.newProducer(Schema.STRING)
            .topic(topic)
            .create();
    }

    public void publishPoisonMessages(int count) throws PulsarClientException {
        for (int i = 0; i < count; i++) {
            String payload = "poison-" + i;
            producer.send(payload);
            LOG.info("Published poison message {}", payload);
        }
    }

    public void send(String payload) throws PulsarClientException {
        producer.send(payload);
    }

    @Override
    public void close() throws PulsarClientException {
        producer.close();
    }
}
