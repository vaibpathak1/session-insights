package io.sessioninsights.processor.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.sessioninsights.processor.ProcessorMetrics;
import io.sessioninsights.processor.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class StoreOutageTrackerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StoreOutageTracker tracker = new StoreOutageTracker(new ProcessorMetrics(registry));

    @Test
    void logsOncePerOutageAndCountsEveryAttempt(CapturedOutput output) {
        for (int i = 0; i < 50; i++) {
            tracker.failed(Store.CLICKHOUSE, "ConnectionInitiationException");
        }
        assertThat(tracker.isUnavailable(Store.CLICKHOUSE)).isTrue();
        assertThat(gauge(Store.CLICKHOUSE)).isEqualTo(1.0);
        tracker.recovered();
        tracker.recovered();

        assertThat(output.getAll().split("clickhouse unavailable", -1)).hasSize(2);
        assertThat(output.getAll().split("clickhouse available again", -1)).hasSize(2);
        assertThat(output.getAll()).contains("(50 failed attempts)");
        assertThat(registry.get(ProcessorMetrics.STORE_RETRIES).tag("store", "clickhouse").counter().count()).isEqualTo(50);
        assertThat(gauge(Store.CLICKHOUSE)).isZero();
        assertThat(tracker.isUnavailable(Store.CLICKHOUSE)).isFalse();
    }

    private double gauge(Store store) {
        return registry.get(ProcessorMetrics.STORE_UNAVAILABLE).tag("store", store.tag()).gauge().value();
    }
}
