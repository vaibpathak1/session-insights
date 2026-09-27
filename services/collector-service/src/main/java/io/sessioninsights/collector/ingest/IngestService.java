package io.sessioninsights.collector.ingest;

import io.sessioninsights.collector.CollectorMetrics;
import io.sessioninsights.collector.config.CollectorProperties;
import io.sessioninsights.collector.kafka.EventPublisher;
import io.sessioninsights.collector.tenant.ResolvedSite;
import io.sessioninsights.collector.tenant.SiteKeyHash;
import io.sessioninsights.collector.tenant.SiteKeyResolver;
import io.sessioninsights.collector.web.OriginMatcher;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventBatch;
import io.sessioninsights.common.wire.ReplayBatch;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The ingestion pipeline: key → origin → rate limit → body → validate → Kafka (task 2.4).
 * Tenant and site always come from the resolved key, never from the client.
 */
@Service
public class IngestService {

    private static final byte[] SCHEMA_VERSION =
            String.valueOf(WireHeaders.CURRENT_SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8);

    private final SiteKeyResolver resolver;
    private final RateLimiter rateLimiter;
    private final BatchValidator validator;
    private final EventPublisher publisher;
    private final CollectorMetrics metrics;
    private final CollectorProperties.Limits limits;
    private final Clock clock;

    public IngestService(SiteKeyResolver resolver, RateLimiter rateLimiter, BatchValidator validator,
                         EventPublisher publisher, CollectorMetrics metrics, CollectorProperties properties,
                         Clock clock) {
        this.resolver = resolver;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.publisher = publisher;
        this.metrics = metrics;
        this.limits = properties.limits();
        this.clock = clock;
    }

    public IngestResult events(IngestRequest request) {
        // With the key in a header or query parameter, refuse before reading the body.
        ResolvedSite site = request.key() != null ? authorize(request.key(), request) : null;
        BatchValidator.ParsedEvents parsed = validator.parseEvents(readBody(request, limits.maxEventsBody().toBytes()));
        EventBatch batch = parsed.batch();
        if (site == null) {
            site = authorize(batch.siteKey(), request);
        }
        Instant receivedAt = clock.instant();
        BatchValidator.ValidatedEvents validated = validator.validate(parsed, receivedAt);

        String anonymousId = validator.anonymousId(batch);
        String sdkVersion = validator.sdkVersion(batch);
        ResolvedSite resolved = site;
        List<ProducerRecord<String, byte[]>> records = validated.accepted().stream()
                .map(event -> record(Topics.TELEMETRY_EVENTS, batch.sessionId(), resolved.tenantId(),
                        new TelemetryEnvelope(WireHeaders.CURRENT_SCHEMA_VERSION, resolved.tenantId(), resolved.siteId(),
                                batch.sessionId(), anonymousId, sdkVersion, receivedAt, event)))
                .toList();
        if (!records.isEmpty()) {
            publisher.publish(Topics.TELEMETRY_EVENTS, records);
        }
        metrics.accepted(records.size());
        metrics.dropped(CollectorMetrics.DROPPED_UNKNOWN_TYPE, validated.droppedUnknownType());
        metrics.dropped(CollectorMetrics.DROPPED_TS_OUT_OF_WINDOW, validated.droppedOutOfWindow());
        return new IngestResult(records.size(), validated.dropped());
    }

    public IngestResult replay(IngestRequest request) {
        ResolvedSite site = request.key() != null ? authorize(request.key(), request) : null;
        ReplayBatch batch = validator.parseReplay(readBody(request, limits.maxReplayBody().toBytes()));
        if (site == null) {
            site = authorize(batch.siteKey(), request);
        }
        validator.validate(batch);

        ReplayEnvelope envelope = new ReplayEnvelope(WireHeaders.CURRENT_SCHEMA_VERSION, site.tenantId(), site.siteId(),
                batch.sessionId(), batch.chunkSeq(), clock.instant(), batch.payload(), batch.events());
        publisher.publish(Topics.REPLAY_CHUNKS, List.of(record(Topics.REPLAY_CHUNKS, batch.sessionId(), site.tenantId(), envelope)));
        return new IngestResult(1, 0);
    }

    private ResolvedSite authorize(String key, IngestRequest request) {
        if (key == null || key.isBlank()) {
            throw new IngestException(Rejection.MISSING_KEY);
        }
        if (key.length() > limits.maxSiteKeyLength()) {
            throw new IngestException(Rejection.UNKNOWN_KEY);
        }
        String keyHash = SiteKeyHash.of(key);
        ResolvedSite site = resolver.resolve(keyHash).orElseThrow(() -> new IngestException(Rejection.UNKNOWN_KEY));
        if (!OriginMatcher.matches(site.allowedOrigins(), request.origin())) {
            throw new IngestException(Rejection.ORIGIN_NOT_ALLOWED);
        }
        request.onOriginAllowed().run();
        rateLimiter.acquire(keyHash);
        return site;
    }

    private static byte[] readBody(IngestRequest request, long limit) {
        return RequestBodyReader.read(request.body(), request.contentEncoding(), request.contentLength(), limit);
    }

    private static ProducerRecord<String, byte[]> record(String topic, UUID sessionId, UUID tenantId, Object envelope) {
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(topic, sessionId.toString(), WireJson.mapper().writeValueAsBytes(envelope));
        record.headers()
                .add(new RecordHeader(WireHeaders.TENANT_ID, tenantId.toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(WireHeaders.SCHEMA_VERSION, SCHEMA_VERSION));
        return record;
    }
}
