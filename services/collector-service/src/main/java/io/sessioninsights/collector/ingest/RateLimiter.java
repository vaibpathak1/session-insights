package io.sessioninsights.collector.ingest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.sessioninsights.collector.config.CollectorProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Token bucket per site key, in memory, per collector instance (ADR-0010). Buckets use
 * Bucket4j's default lock-free strategy (no {@code synchronized}, no virtual-thread pinning)
 * and are evicted when a key has been idle for a while.
 */
@Component
public class RateLimiter {

    private final CollectorProperties.RateLimit config;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(100_000)
            .build();

    public RateLimiter(CollectorProperties properties) {
        this.config = properties.rateLimit();
    }

    /** Takes one token for the key, or throws {@code 429} with the seconds until the next one. */
    public void acquire(String keyHash) {
        ConsumptionProbe probe = buckets.get(keyHash, k -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
            throw new IngestException(Rejection.RATE_LIMITED, seconds);
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(config.capacity()).refillGreedy(config.refillTokens(), config.refillPeriod()))
                .build();
    }
}
