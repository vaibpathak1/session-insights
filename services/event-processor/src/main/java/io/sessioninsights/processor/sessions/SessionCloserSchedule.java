package io.sessioninsights.processor.sessions;

import io.sessioninsights.processor.ProcessorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link SessionCloser} every {@code interval} on one dedicated platform thread (JDBC, the
 * ClickHouse client and the Kafka producer all block; ADR-0011 keeps such work off virtual
 * threads when libraries lock around it). A failing pass is rolled back and retried at the next
 * interval; one WARN per failure streak, one INFO when passes succeed again.
 */
public class SessionCloserSchedule implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SessionCloserSchedule.class);

    private final SessionCloser closer;
    private final ProcessorMetrics metrics;
    private final Duration interval;
    private ScheduledExecutorService executor;
    private volatile boolean failing;

    public SessionCloserSchedule(SessionCloser closer, ProcessorMetrics metrics, Duration interval) {
        this.closer = closer;
        this.metrics = metrics;
        this.interval = interval;
    }

    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("session-closer").daemon().factory());
        executor.scheduleWithFixedDelay(this::runPasses, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    void runPasses() {
        try {
            closer.closeAll();
            if (failing) {
                failing = false;
                log.info("Session closer passes succeed again");
            }
        } catch (RuntimeException e) {
            metrics.closerFailed();
            if (!failing) {
                failing = true;
                // class names only: driver and client messages can quote values
                log.warn("Session closer pass rolled back ({}{}); retrying every {}", e.getClass().getSimpleName(),
                        e.getCause() == null ? "" : "/" + e.getCause().getClass().getSimpleName(), interval);
            }
        }
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }
}
