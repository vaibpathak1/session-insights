package io.sessioninsights.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Processor settings ({@code processor.*}); defaults in {@code application.yml} match {@code .env.example}. */
@ConfigurationProperties("processor")
public record ProcessorProperties(ClickHouse clickhouse, S3 s3, Retry retry, Replay replay, Sessions sessions) {

    /** The ClickHouse HTTP endpoint and the user the processor inserts as. */
    public record ClickHouse(String endpoint, String database, String username, String password,
                             Duration connectTimeout, Duration socketTimeout, Duration insertTimeout) {
    }

    /** S3-compatible replay storage (path-style access, e.g. SeaweedFS). */
    public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey,
                     int maxConcurrency, Duration apiCallTimeout) {
    }

    /** Back-off while a store is unavailable: exponential, capped, without an attempt limit. */
    public record Retry(Duration initialInterval, double multiplier, Duration maxInterval) {
    }

    public record Replay(int zstdLevel) {
    }

    /** Session lifecycle (task 5.4). */
    public record Sessions(Duration idleTimeout, Closer closer) {
    }

    /**
     * The closer: every {@code interval}, claims up to {@code batchSize} sessions per
     * transaction. {@code statementTimeout} bounds each SQL statement and {@code publishTimeout}
     * the wait for the lifecycle acks; either ends the pass with a rollback.
     */
    public record Closer(boolean enabled, Duration interval, int batchSize, Duration statementTimeout,
                         Duration publishTimeout) {
    }
}
