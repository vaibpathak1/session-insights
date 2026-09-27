package io.sessioninsights.collector;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sessioninsights.collector.ingest.Rejection;
import org.springframework.stereotype.Component;

/**
 * Collector meters (task 2.9). Tags are fixed, low-cardinality codes: never a site key,
 * tenant id or anything from a payload.
 */
@Component
public class CollectorMetrics {

    public static final String EVENTS_ACCEPTED = "si.collector.events.accepted";
    public static final String EVENTS_DROPPED = "si.collector.events.dropped";
    public static final String REQUESTS_REJECTED = "si.collector.requests.rejected";
    public static final String KAFKA_SEND = "si.collector.kafka.send";

    /** Drop reason for events whose client timestamp is outside the accepted window. */
    public static final String DROPPED_TS_OUT_OF_WINDOW = "ts_out_of_window";

    private final MeterRegistry registry;
    private final Counter accepted;

    public CollectorMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.accepted = Counter.builder(EVENTS_ACCEPTED)
                .description("Telemetry events acknowledged by Kafka")
                .register(registry);
    }

    public void accepted(int events) {
        accepted.increment(events);
    }

    public void dropped(String reason, int events) {
        if (events > 0) {
            Counter.builder(EVENTS_DROPPED).tag("reason", reason).register(registry).increment(events);
        }
    }

    public void rejected(Rejection rejection) {
        Counter.builder(REQUESTS_REJECTED).tag("reason", rejection.reason()).register(registry).increment();
    }

    public Timer.Sample startKafkaSend() {
        return Timer.start(registry);
    }

    public void stopKafkaSend(Timer.Sample sample, String topic, boolean success) {
        sample.stop(Timer.builder(KAFKA_SEND)
                .description("Time to send a request's records and receive all acks")
                .tag("topic", topic)
                .tag("outcome", success ? "success" : "failure")
                .register(registry));
    }
}
