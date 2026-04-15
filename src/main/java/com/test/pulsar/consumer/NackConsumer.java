package com.test.pulsar.consumer;

import com.test.pulsar.config.TopicNames;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.MultiplierRedeliveryBackoff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NackConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NackConsumer.class);

    public static final int MAX_REDELIVER_COUNT = 10;
    public static final long MIN_BACKOFF_MS = 1_000;
    public static final long MAX_BACKOFF_MS = 60_000;

    private final Consumer<String> consumer;
    private final AtomicInteger maxRedeliveryCountSeen = new AtomicInteger(0);
    private final AtomicInteger messagesReceivedCount = new AtomicInteger(0);
    private final AtomicInteger messagesAckedCount = new AtomicInteger(0);
    private final AtomicInteger messagesNackedCount = new AtomicInteger(0);

    public NackConsumer(PulsarClient client, TopicNames names) throws PulsarClientException {
        this(client, names, msg -> false);
    }

    public NackConsumer(PulsarClient client, TopicNames names,
                        Predicate<Message<String>> shouldAck)
            throws PulsarClientException {
        this.consumer = client.newConsumer(Schema.STRING)
            .topic(names.main())
            .subscriptionName(names.subscription())
            .subscriptionType(SubscriptionType.Shared)
            .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
            // PAS de enableRetry(true) — c'est le cœur du bug
            .deadLetterPolicy(
                DeadLetterPolicy.builder()
                    .maxRedeliverCount(MAX_REDELIVER_COUNT)
                    .deadLetterTopic(names.dlq())
                    .build()
            )
            .negativeAckRedeliveryBackoff(
                MultiplierRedeliveryBackoff.builder()
                    .minDelayMs(MIN_BACKOFF_MS)
                    .maxDelayMs(MAX_BACKOFF_MS)
                    .multiplier(2)
                    .build()
            )
            .messageListener((c, msg) -> {
                int rc = msg.getRedeliveryCount();
                messagesReceivedCount.incrementAndGet();
                maxRedeliveryCountSeen.updateAndGet(prev -> Math.max(prev, rc));
                LOG.info("Received id={} redeliveryCount={} payload={}",
                    msg.getMessageId(), rc, new String(msg.getData()));
                if (shouldAck.test(msg)) {
                    try {
                        c.acknowledge(msg);
                        messagesAckedCount.incrementAndGet();
                    } catch (PulsarClientException e) {
                        LOG.error("ack failed", e);
                    }
                } else {
                    messagesNackedCount.incrementAndGet();
                    c.negativeAcknowledge(msg);
                }
            })
            .subscribe();
        LOG.info("NackConsumer subscribed topic={} subscription={}", names.main(), names.subscription());
    }

    public int getMessagesAckedCount() {
        return messagesAckedCount.get();
    }

    public int getMessagesNackedCount() {
        return messagesNackedCount.get();
    }

    public int getMaxRedeliveryCountSeen() {
        return maxRedeliveryCountSeen.get();
    }

    public int getMessagesReceivedCount() {
        return messagesReceivedCount.get();
    }

    @Override
    public void close() throws PulsarClientException {
        consumer.close();
        LOG.info("NackConsumer closed");
    }
}
