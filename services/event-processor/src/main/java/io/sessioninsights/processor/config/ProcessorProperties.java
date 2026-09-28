package io.sessioninsights.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Processor settings ({@code processor.*}); defaults in {@code application.yml} match {@code .env.example}. */
@ConfigurationProperties("processor")
public record ProcessorProperties(ClickHouse clickhouse, S3 s3, Retry retry, Replay replay) {

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
}
