package io.sessioninsights.processor.kafka;

import io.sessioninsights.processor.store.Store;
import io.sessioninsights.processor.store.StoreUnavailableException;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerUtils;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Failure handling for the batch listeners (ADR-0011).
 * <ul>
 *   <li><b>Poison</b> ({@link BatchListenerFailedException}): no retries; offsets before the
 *       record are committed, the record is dead-lettered, the rest is redelivered
 *       ({@link DefaultErrorHandler}).</li>
 *   <li><b>Anything else</b> (a store outage, or an unexpected failure): never dead-lettered.
 *       The consumer is paused, keeps polling so it stays in the group, and re-invokes the
 *       listener with the same batch under exponential back-off, without limit. Nothing is
 *       committed until the batch is written. If the container stops meanwhile, the
 *       partitions are rewound to the batch start.</li>
 * </ul>
 * Spring's own batch retry is not used for outages: when the store comes back and the batch
 * turns out to contain a poison record, it would hand the whole batch to the recoverer.
 */
public class ConsumerErrorHandler extends DefaultErrorHandler {

    private final BackOff outageBackOff;
    private final StoreOutageTracker outages;
    private final ThreadLocal<Boolean> retrying = ThreadLocal.withInitial(() -> false);

    public ConsumerErrorHandler(ConsumerRecordRecoverer deadLetters, BackOff outageBackOff, StoreOutageTracker outages) {
        super(deadLetters, new FixedBackOff(0, 0));
        this.outageBackOff = outageBackOff;
        this.outages = outages;
        // failures are reported by StoreOutageTracker and the DLT recoverer, without stack traces
        setLogLevel(KafkaException.Level.DEBUG);
    }

    @Override
    public void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
                            MessageListenerContainer container, Runnable invokeListener) {
        if (data == null || data.isEmpty() || isPoison(thrownException)) {
            super.handleBatch(thrownException, data, consumer, container, invokeListener);
            return;
        }
        retryUntilWritten(thrownException, data, consumer, container, invokeListener);
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions,
                                     Runnable publishPause) {
        if (retrying.get()) {
            // a rebalance during an outage must not let polls return (and drop) new records
            consumer.pause(consumer.assignment());
            publishPause.run();
        } else {
            super.onPartitionsAssigned(consumer, partitions, publishPause);
        }
    }

    private void retryUntilWritten(Exception thrownException, ConsumerRecords<?, ?> data, Consumer<?, ?> consumer,
                                   MessageListenerContainer container, Runnable invokeListener) {
        outages.failed(storeOf(thrownException), describe(thrownException));
        BackOffExecution backOff = outageBackOff.start();
        retrying.set(true);
        consumer.pause(consumer.assignment());
        Exception poison = null;
        try {
            while (true) {
                try {
                    ListenerUtils.conditionalSleepWithPoll(
                            () -> container.isRunning() && !container.isPauseRequested(),
                            backOff.nextBackOff(), consumer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    rewind(data, consumer);
                    throw new KafkaException("Interrupted while a store was unavailable", KafkaException.Level.INFO, e);
                }
                if (!container.isRunning() || container.isPauseRequested()) {
                    rewind(data, consumer);
                    throw new KafkaException("Container stopped or paused while a store was unavailable",
                            KafkaException.Level.INFO, null);
                }
                try {
                    invokeListener.run();
                    outages.recovered();
                    return;   // written: the container commits the batch
                } catch (Exception e) {
                    if (isPoison(e)) {
                        outages.recovered();
                        poison = e;
                        break;
                    }
                    outages.failed(storeOf(e), describe(e));
                }
            }
        } finally {
            retrying.set(false);
            consumer.resume(consumer.assignment());
        }
        // the stores are back and the batch has a bad record: good records are written
        super.handleBatch(poison, data, consumer, container, invokeListener);
    }

    /** Seeks every partition of the batch back to its first record, so nothing is skipped. */
    private static void rewind(ConsumerRecords<?, ?> data, Consumer<?, ?> consumer) {
        Map<TopicPartition, Long> first = new LinkedHashMap<>();
        for (ConsumerRecord<?, ?> record : data) {
            first.putIfAbsent(new TopicPartition(record.topic(), record.partition()), record.offset());
        }
        first.forEach(consumer::seek);
    }

    static boolean isPoison(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BatchListenerFailedException) {
                return true;
            }
        }
        return false;
    }

    static Store storeOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof StoreUnavailableException unavailable) {
                return unavailable.store();
            }
        }
        return Store.UNKNOWN;
    }

    /** Exception classes only: messages of unexpected exceptions could carry data. */
    private static String describe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof StoreUnavailableException unavailable) {
                return unavailable.detail();
            }
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName();
    }
}
