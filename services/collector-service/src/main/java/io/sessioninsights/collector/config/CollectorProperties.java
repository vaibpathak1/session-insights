package io.sessioninsights.collector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Collector limits and tuning; every value has a safe default. */
@ConfigurationProperties("collector")
public record CollectorProperties(
        @DefaultValue Limits limits,
        @DefaultValue KeyCache keyCache,
        @DefaultValue RateLimit rateLimit,
        // upper bound on waiting for Kafka acks; must exceed spring.kafka delivery.timeout.ms
        @DefaultValue("10s") Duration kafkaSendTimeout) {

    /** Request limits (task 2.6). Body sizes are measured after decompression. */
    public record Limits(
            @DefaultValue("500") int maxEvents,
            @DefaultValue("1MB") DataSize maxEventsBody,
            // decompressed; the zstd-compressed Kafka record must still fit replay.chunks.v1
            // max.message.bytes (4 MB, infra/kafka/create-topics.sh), enforced by the broker → 413
            @DefaultValue("16MB") DataSize maxReplayBody,
            @DefaultValue("24h") Duration maxEventAge,
            @DefaultValue("5m") Duration maxClockSkew,
            @DefaultValue("256") int maxSiteKeyLength,
            @DefaultValue FieldLimits fields) {
    }

    /** Maximum string lengths (characters); longer values are truncated, not rejected. */
    public record FieldLimits(
            @DefaultValue("2048") int url,
            @DefaultValue("1024") int path,
            @DefaultValue("512") int title,
            @DefaultValue("1024") int targetSelector,
            @DefaultValue("1024") int targetText,
            @DefaultValue("2048") int errorMessage,
            @DefaultValue("8192") int errorStack,
            @DefaultValue("128") int eventName,
            @DefaultValue("128") int anonymousId,
            @DefaultValue("32") int sdkVersion,
            @DefaultValue("512") int userAgent) {
    }

    /** Site key cache (ADR-0010): revocation takes effect within {@code positiveTtl}. */
    public record KeyCache(
            @DefaultValue("60s") Duration positiveTtl,
            @DefaultValue("10s") Duration negativeTtl,
            @DefaultValue("10000") long maximumSize) {
    }

    /** Per site key token bucket, per collector instance (ADR-0010). */
    public record RateLimit(
            @DefaultValue("100") long capacity,
            @DefaultValue("50") long refillTokens,
            @DefaultValue("1s") Duration refillPeriod) {
    }
}
