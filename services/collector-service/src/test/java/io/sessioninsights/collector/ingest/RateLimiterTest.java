package io.sessioninsights.collector.ingest;

import io.sessioninsights.collector.config.CollectorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    @Test
    void defaultsAllowABurstOf100PerKey() {
        RateLimiter limiter = new RateLimiter(BatchValidatorTest.defaults());
        for (int i = 0; i < 100; i++) {
            limiter.acquire("key-a");
        }
        assertThatThrownBy(() -> limiter.acquire("key-a")).isInstanceOfSatisfying(IngestException.class, e -> {
            assertThat(e.rejection()).isEqualTo(Rejection.RATE_LIMITED);
            assertThat(e.retryAfterSeconds()).isEqualTo(1);
        });
        limiter.acquire("key-b");   // buckets are per key
    }

    @Test
    void retryAfterRoundsUpToWholeSeconds() {
        CollectorProperties properties = new Binder(new MapConfigurationPropertySource(Map.of(
                "collector.rate-limit.capacity", "1",
                "collector.rate-limit.refill-tokens", "1",
                "collector.rate-limit.refill-period", "90s")))
                .bindOrCreate("collector", CollectorProperties.class);
        RateLimiter limiter = new RateLimiter(properties);
        limiter.acquire("k");

        assertThatThrownBy(() -> limiter.acquire("k")).isInstanceOfSatisfying(IngestException.class,
                e -> assertThat(e.retryAfterSeconds()).isBetween(89L, 90L));
    }
}
