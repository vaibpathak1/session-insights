package io.sessioninsights.processor.kafka;

import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One WARN when a store outage starts and one INFO when it ends, however many consumers and
 * retries are involved in between (ADR-0011). Every failed attempt is still counted in
 * {@code si.processor.store.retries}, and {@code si.processor.store.unavailable} is 1 meanwhile.
 */
@Component
public class StoreOutageTracker {

    private static final Logger log = LoggerFactory.getLogger(StoreOutageTracker.class);

    private final ProcessorMetrics metrics;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Store, Outage> outages = new EnumMap<>(Store.class);

    public StoreOutageTracker(ProcessorMetrics metrics) {
        this.metrics = metrics;
    }

    /** A batch attempt failed because {@code store} was unavailable; {@code detail} has no payload. */
    public void failed(Store store, String detail) {
        metrics.storeRetry(store);
        lock.lock();
        try {
            Outage outage = outages.get(store);
            if (outage != null) {
                outage.attempts++;
                return;
            }
            outages.put(store, new Outage(Instant.now()));
        } finally {
            lock.unlock();
        }
        metrics.storeUnavailable(store, true);
        if (store == Store.UNKNOWN) {
            log.error("Unexpected failure writing a batch ({}); pausing consumption and retrying with back-off. "
                    + "Offsets are not committed and nothing is dead-lettered.", detail);
        } else {
            log.warn("{} unavailable ({}); pausing consumption and retrying with back-off. "
                    + "Offsets are not committed and nothing is dead-lettered.", store.tag(), detail);
        }
    }

    /** A batch was written after failures; ends every open outage. */
    public void recovered() {
        for (Store store : Store.values()) {
            Outage outage;
            lock.lock();
            try {
                outage = outages.remove(store);
            } finally {
                lock.unlock();
            }
            if (outage != null) {
                metrics.storeUnavailable(store, false);
                log.info("{} available again after {} ({} failed attempts); consumption resumed",
                        store.tag(), Duration.between(outage.since, Instant.now()).withNanos(0), outage.attempts);
            }
        }
    }

    public boolean isUnavailable(Store store) {
        lock.lock();
        try {
            return outages.containsKey(store);
        } finally {
            lock.unlock();
        }
    }

    private static final class Outage {
        private final Instant since;
        private int attempts = 1;

        private Outage(Instant since) {
            this.since = since;
        }
    }
}
