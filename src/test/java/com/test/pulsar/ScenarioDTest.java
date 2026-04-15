package com.test.pulsar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.test.pulsar.consumer.NackConsumer;
import com.test.pulsar.util.PulsarMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Scénario D — perte du tracker sans restart broker (bundle/topic unload).
 *
 * Les scénarios A et B sur-contraignent la reproduction avec un vrai restart
 * du conteneur Pulsar. En prod, l'{@code InMemoryRedeliveryTracker} est
 * invalidé bien plus souvent par des opérations de routine : rebalancing de
 * bundle, unload d'un bundle inactif, recréation d'un dispatcher après que le
 * dernier consumer se déconnecte. Un restart broker n'est qu'un cas parmi
 * d'autres.
 *
 * Ce scénario prouve que le bug ne dépend pas du restart : un simple
 * {@code topics unload} via pulsar-admin — opération sans downtime, que
 * Pulsar effectue seul en régime nominal — suffit à perdre le compteur et
 * à ramener les redeliveries à zéro.
 */
class ScenarioDTest extends AbstractPulsarScenarioTest {

    private static final Duration INITIAL_NACK_CYCLES = Duration.ofSeconds(9);
    private static final Duration POST_UNLOAD_OBSERVATION = Duration.ofSeconds(5);
    private static final Duration DLQ_STAY_EMPTY_WINDOW = Duration.ofSeconds(20);

    private NackConsumer consumer;

    @Override
    protected String topicPrefix() {
        return "test-nack-unload-scenario";
    }

    @AfterEach
    void closeConsumer() {
        closeQuietly(consumer);
    }

    @Test
    void topicUnloadResetsRedeliveryTracker() throws Exception {
        producer.publishPoisonMessages(10);
        consumer = new NackConsumer(client, names);

        Thread.sleep(INITIAL_NACK_CYCLES.toMillis());

        int countBeforeUnload = consumer.getMaxRedeliveryCountSeen();
        LOG.info("=== Avant unload : maxRedeliveryCountSeen={} messagesReceived={}",
            countBeforeUnload, consumer.getMessagesReceivedCount());
        assertTrue(countBeforeUnload >= 2,
            "Le redeliveryCount doit avoir monté à au moins 2 avant l'unload (vu=" + countBeforeUnload + ")");

        // Snapshot le compteur juste avant l'unload pour pouvoir mesurer le reset :
        // le nouveau dispatcher repart avec un tracker vierge, donc les prochaines
        // deliveries doivent repartir de rc=0 puis monter lentement.
        int receivedBeforeUnload = consumer.getMessagesReceivedCount();

        // pulsar-admin topics unload : ferme le dispatcher côté broker, détruit
        // l'InMemoryRedeliveryTracker, puis recharge le topic. Le broker reste
        // up, le consumer se réattache transparent. C'est ce qui se passe en
        // prod quand un bundle est rééquilibré ou unloaded par inactivité.
        LOG.info("=== pulsar-admin topics unload {}", names.main());
        dockerExec("bin/pulsar-admin", "topics", "unload", names.main());
        LOG.info("=== topic unloaded");

        Thread.sleep(POST_UNLOAD_OBSERVATION.toMillis());

        int lastRcAfterUnload = consumer.getLastRedeliveryCountSeen();
        int receivedAfterUnload = consumer.getMessagesReceivedCount();
        int observedSinceUnload = receivedAfterUnload - receivedBeforeUnload;
        LOG.info("=== Après unload : lastRedeliveryCountSeen={} messagesReceivedSinceUnload={}",
            lastRcAfterUnload, observedSinceUnload);

        assertTrue(observedSinceUnload > 0,
            "Le consumer aurait dû se réattacher et recevoir des redeliveries après l'unload "
                + "(vu=" + observedSinceUnload + ")");

        // Preuve forte du reset : le dernier rc observé après le unload doit être
        // strictement inférieur au max observé avant. Sans reset, il ne ferait que
        // monter (le tracker broker est monotone sur la durée de vie du dispatcher).
        // Avec reset, le nouveau dispatcher recommence à rc=0 et on voit typiquement
        // 0 ou 1 dans les premières secondes post-unload.
        assertTrue(lastRcAfterUnload < countBeforeUnload,
            "Le dernier redeliveryCount observé après unload (" + lastRcAfterUnload
                + ") devrait être strictement inférieur au max pré-unload ("
                + countBeforeUnload + ") — sinon le tracker n'a pas été réinitialisé");

        Thread.sleep(DLQ_STAY_EMPTY_WINDOW.toMillis());
        long dlqMsgIn = PulsarMetrics.getMsgInCounter(names.dlq());
        LOG.info("=== Après {}s supplémentaires : dlqMsgInCounter={}",
            DLQ_STAY_EMPTY_WINDOW.toSeconds(), dlqMsgIn);
        assertTrue(dlqMsgIn == 0,
            "La DLQ doit rester vide : un unload suffit à invalider le tracker, "
                + "le seuil maxRedeliverCount n'est jamais atteint dans la fenêtre du test "
                + "(vu=" + dlqMsgIn + ")");
    }
}
