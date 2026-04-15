package com.test.pulsar.consumer;

import com.test.pulsar.config.TopicNames;
import java.util.concurrent.TimeUnit;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReconsumeLaterConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ReconsumeLaterConsumer.class);

    // Réduits pour accélérer les tests (prod : 10 / 30 s). Les tests B et C
    // dérivent leurs durées d'attente de ces constantes — ne pas les modifier
    // sans ajuster les assertions.
    public static final int MAX_REDELIVER_COUNT = 3;
    public static final long RECONSUME_DELAY_SECONDS = 2;

    private final Consumer<String> consumer;
    private final AtomicInteger maxReconsumeTimesSeen = new AtomicInteger(0);
    private final AtomicInteger messagesReceivedCount = new AtomicInteger(0);
    private final AtomicInteger messagesAckedCount = new AtomicInteger(0);
    private final AtomicInteger messagesReconsumedCount = new AtomicInteger(0);

    public ReconsumeLaterConsumer(PulsarClient client, TopicNames names) throws PulsarClientException {
        this(client, names, msg -> false);
    }

    public ReconsumeLaterConsumer(PulsarClient client, TopicNames names,
                                   Predicate<Message<String>> shouldAck)
            throws PulsarClientException {
        this.consumer = client.newConsumer(Schema.STRING)
            .topic(names.main())
            .subscriptionName(names.subscription())
            .subscriptionType(SubscriptionType.Shared)
            .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
            .enableRetry(true) // active reconsumeLater et RECONSUMETIMES persisté dans BK
            .deadLetterPolicy(
                DeadLetterPolicy.builder()
                    .maxRedeliverCount(MAX_REDELIVER_COUNT)
                    .retryLetterTopic(names.retry())
                    .deadLetterTopic(names.dlq())
                    .build()
            )
            .messageListener((c, msg) -> {
                int reconsumeTimes = getReconsumeCount(msg);
                messagesReceivedCount.incrementAndGet();
                maxReconsumeTimesSeen.updateAndGet(prev -> Math.max(prev, reconsumeTimes));
                LOG.info("Received id={} RECONSUMETIMES={} redeliveryCount={} payload={}",
                    msg.getMessageId(), reconsumeTimes, msg.getRedeliveryCount(), new String(msg.getData()));
                if (shouldAck.test(msg)) {
                    try {
                        c.acknowledge(msg);
                        messagesAckedCount.incrementAndGet();
                    } catch (PulsarClientException e) {
                        LOG.error("ack failed", e);
                    }
                } else {
                    try {
                        c.reconsumeLater(msg, RECONSUME_DELAY_SECONDS, TimeUnit.SECONDS);
                        messagesReconsumedCount.incrementAndGet();
                    } catch (PulsarClientException e) {
                        LOG.error("reconsumeLater failed, falling back to nack", e);
                        c.negativeAcknowledge(msg);
                    }
                }
            })
            .subscribe();
        LOG.info("ReconsumeLaterConsumer subscribed topic={} subscription={}", names.main(), names.subscription());
    }

    private static int getReconsumeCount(Message<?> msg) {
        String val = msg.getProperty("RECONSUMETIMES");
        if (val == null) return 0;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            LOG.warn("Malformed RECONSUMETIMES property: {}", val);
            return 0;
        }
    }

    public int getMaxReconsumeTimesSeen() {
        return maxReconsumeTimesSeen.get();
    }

    public int getMessagesReceivedCount() {
        return messagesReceivedCount.get();
    }

    public int getMessagesAckedCount() {
        return messagesAckedCount.get();
    }

    public int getMessagesReconsumedCount() {
        return messagesReconsumedCount.get();
    }

    @Override
    public void close() throws PulsarClientException {
        consumer.close();
        LOG.info("ReconsumeLaterConsumer closed");
    }
}
