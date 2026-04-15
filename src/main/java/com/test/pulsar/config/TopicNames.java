package com.test.pulsar.config;

import java.util.UUID;

/**
 * Single source of truth for topic naming. The retry and DLQ suffixes are
 * computed here and passed explicitly to {@code DeadLetterPolicy}, so tests
 * and consumers never disagree on the names (finding: A and B were using
 * different DLQ conventions and almost drifted).
 */
public record TopicNames(String main, String subscription, String retry, String dlq) {

    public static TopicNames forTest(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String main = "persistent://public/default/" + prefix + "-" + suffix;
        String subscription = "test-sub-" + suffix;
        return new TopicNames(main, subscription, main + "-RETRY", main + "-DLQ");
    }
}
