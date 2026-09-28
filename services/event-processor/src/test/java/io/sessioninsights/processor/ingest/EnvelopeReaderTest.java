package io.sessioninsights.processor.ingest;

import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.Fixtures;
import io.sessioninsights.processor.Fixtures.Session;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeReaderTest {

    private final Session s = Session.random();

    @Test
    void readsAValidTelemetryEnvelope() {
        TelemetryEnvelope envelope = Fixtures.envelope(s, Fixtures.click(1L, "t", "{\"a\":1}"));
        assertThat(EnvelopeReader.telemetry(bytes(envelope))).isEqualTo(envelope);
    }

    @Test
    void telemetryReasons() {
        var event = Fixtures.click(1L, "t", null);
        assertReason(bytes(new TelemetryEnvelope(2, s.tenantId(), s.siteId(), s.sessionId(), "a", "v", NOW, event)),
                DltReason.UNSUPPORTED_SCHEMA_VERSION);
        assertReason(bytes(new TelemetryEnvelope(1, null, s.siteId(), s.sessionId(), "a", "v", NOW, event)),
                DltReason.MISSING_TENANT);
        assertReason(bytes(new TelemetryEnvelope(1, s.tenantId(), null, s.sessionId(), "a", "v", NOW, event)),
                DltReason.MISSING_SITE);
        assertReason(bytes(new TelemetryEnvelope(1, s.tenantId(), s.siteId(), null, "a", "v", NOW, event)),
                DltReason.MISSING_SESSION);
        assertReason(bytes(new TelemetryEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), "a", "v", NOW, null)),
                DltReason.MISSING_EVENT);
        String valid = new String(bytes(Fixtures.envelope(s, event)), StandardCharsets.UTF_8);
        assertReason(valid.replace("\"clientEventId\":\"" + event.clientEventId() + "\",", ""), DltReason.MISSING_EVENT_ID);
        assertReason(valid.replace("\"type\":\"CLICK\",", ""), DltReason.MISSING_TYPE);
        assertReason(valid.replace("\"ts\":1,", ""), DltReason.MISSING_TS);
        assertReason(valid.replace("\"CLICK\"", "\"TELEPORT\""), DltReason.BAD_TYPE);
        assertReason("{\"schemaVersion\":1", DltReason.DESERIALIZATION);
        assertReason("[]", DltReason.DESERIALIZATION);
        assertReason("null", DltReason.DESERIALIZATION);
        assertThatThrownBy(() -> EnvelopeReader.telemetry(new byte[0])).isInstanceOf(PoisonRecordException.class);
        assertThatThrownBy(() -> EnvelopeReader.telemetry(null)).isInstanceOf(PoisonRecordException.class);
    }

    @Test
    void replayReasons() {
        var events = Fixtures.rrwebEvents(1L, 1, true, "x");
        assertThat(EnvelopeReader.replay(bytes(Fixtures.chunk(s, 0, events))).chunkSeq()).isZero();
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0, NOW, "AAAA", null),
                DltReason.UNSUPPORTED_PAYLOAD);
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0, NOW, "AAAA", events),
                DltReason.INVALID_CHUNK);
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), -1, NOW, null, events),
                DltReason.INVALID_CHUNK);
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0, NOW, null, null),
                DltReason.INVALID_CHUNK);
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0, NOW, null,
                WireJson.mapper().readTree("[]")), DltReason.INVALID_CHUNK);
        assertReplayReason(new ReplayEnvelope(1, s.tenantId(), s.siteId(), s.sessionId(), 0, NOW, null,
                WireJson.mapper().readTree("[{\"type\":3}]")), DltReason.INVALID_CHUNK);
        assertReplayReason(new ReplayEnvelope(1, null, s.siteId(), s.sessionId(), 0, NOW, null, events),
                DltReason.MISSING_TENANT);
    }

    @Test
    void poisonMessagesCarryOnlyTheReasonCode() {
        String secret = "secret-" + s.sessionId();
        assertThatThrownBy(() -> EnvelopeReader.telemetry(("{\"x\":\"" + secret).getBytes(StandardCharsets.UTF_8)))
                .hasMessage("deserialization").hasNoCause();
    }

    private static byte[] bytes(Object envelope) {
        return WireJson.mapper().writeValueAsBytes(envelope);
    }

    private static void assertReason(byte[] value, DltReason reason) {
        assertThatThrownBy(() -> EnvelopeReader.telemetry(value))
                .isInstanceOfSatisfying(PoisonRecordException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static void assertReason(String value, DltReason reason) {
        assertReason(value.getBytes(StandardCharsets.UTF_8), reason);
    }

    private static void assertReplayReason(ReplayEnvelope envelope, DltReason reason) {
        assertThatThrownBy(() -> EnvelopeReader.replay(bytes(envelope)))
                .isInstanceOfSatisfying(PoisonRecordException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
