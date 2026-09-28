package io.sessioninsights.processor;

import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Envelopes and Kafka records shaped exactly like the collector produces them. */
public final class Fixtures {

    public static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private Fixtures() {
    }

    /** One tenant/site/session. */
    public record Session(UUID tenantId, UUID siteId, UUID sessionId) {
        public static Session random() {
            return new Session(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        }

        public String key() {
            return sessionId.toString();
        }
    }

    public static TelemetryEvent click(long ts, String targetText, String propsJson) {
        return new TelemetryEvent(UUID.randomUUID(), EventType.CLICK, ts, "http://localhost:5173/cart?step=2", "/cart",
                "Cart", "button#pay", targetText, null, null, null,
                propsJson == null ? null : WireJson.mapper().readTree(propsJson));
    }

    public static TelemetryEnvelope envelope(Session s, TelemetryEvent event) {
        return new TelemetryEnvelope(WireHeaders.CURRENT_SCHEMA_VERSION, s.tenantId(), s.siteId(), s.sessionId(),
                "anon-1", "0.1.0", NOW, event);
    }

    public static ProducerRecord<String, byte[]> eventRecord(Session s, TelemetryEnvelope envelope) {
        return record(Topics.TELEMETRY_EVENTS, s, WireJson.mapper().writeValueAsBytes(envelope));
    }

    public static ProducerRecord<String, byte[]> eventRecord(Session s, TelemetryEvent event) {
        return eventRecord(s, envelope(s, event));
    }

    /** rrweb events: a Meta (4) and a FullSnapshot (2) when {@code full}, then incremental (3) events. */
    public static JsonNode rrwebEvents(long firstTs, int incremental, boolean full, String text) {
        StringBuilder json = new StringBuilder("[");
        long ts = firstTs;
        if (full) {
            json.append("{\"type\":4,\"timestamp\":").append(ts++).append(",\"data\":{\"href\":\"http://localhost/\"}},");
            json.append("{\"type\":2,\"timestamp\":").append(ts++)
                    .append(",\"data\":{\"node\":{\"type\":0,\"childNodes\":[{\"type\":3,\"textContent\":\"")
                    .append(text).append("\"}]}}},");
        }
        for (int i = 0; i < incremental; i++) {
            json.append("{\"type\":3,\"timestamp\":").append(ts++).append(",\"data\":{\"source\":2,\"x\":").append(i)
                    .append("}},");
        }
        json.setLength(json.length() - 1);
        return WireJson.mapper().readTree(json.append(']').toString());
    }

    /** Meta + a FullSnapshot of about {@code bytes} bytes of repetitive DOM JSON (compressible, like real DOMs). */
    public static JsonNode largeSnapshot(long firstTs, int bytes) {
        StringBuilder json = new StringBuilder(bytes + 256)
                .append("[{\"type\":4,\"timestamp\":").append(firstTs).append(",\"data\":{\"href\":\"http://localhost/\"}},")
                .append("{\"type\":2,\"timestamp\":").append(firstTs + 1).append(",\"data\":{\"node\":{\"type\":0,\"childNodes\":[");
        int id = 1;
        while (json.length() < bytes - 200) {
            json.append("{\"type\":2,\"tagName\":\"td\",\"attributes\":{\"class\":\"cell\"},\"childNodes\":[{\"type\":3,\"textContent\":\"row ")
                    .append(id % 997).append("\",\"id\":").append(id + 1).append("}],\"id\":").append(id).append("},");
            id += 2;
        }
        json.append("{\"type\":3,\"textContent\":\"end\",\"id\":0}]}}}]");
        return WireJson.mapper().readTree(json.toString());
    }

    public static ReplayEnvelope chunk(Session s, int chunkSeq, JsonNode events) {
        return new ReplayEnvelope(WireHeaders.CURRENT_SCHEMA_VERSION, s.tenantId(), s.siteId(), s.sessionId(),
                chunkSeq, NOW, null, events);
    }

    public static ProducerRecord<String, byte[]> chunkRecord(Session s, ReplayEnvelope envelope) {
        return record(Topics.REPLAY_CHUNKS, s, WireJson.mapper().writeValueAsBytes(envelope));
    }

    public static ProducerRecord<String, byte[]> record(String topic, Session s, byte[] value) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, s.key(), value);
        record.headers().add(new RecordHeader(WireHeaders.TENANT_ID, s.tenantId().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(WireHeaders.SCHEMA_VERSION, "1".getBytes(StandardCharsets.UTF_8)));
        return record;
    }
}
