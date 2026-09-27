package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorTestInfra;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayEndpointTest extends CollectorIntegrationTest {

    @Test
    void compressedAndRawChunksLandOnReplayTopic() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String payload = Base64.getEncoder().encodeToString("compressed-rrweb".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> compressed = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"payload\":\"%s\",\"tenantId\":\"%s\"}"
                        .formatted(session, payload, UUID.randomUUID()))
                .send();
        HttpResponse<String> raw = post("/v1/replay?k=" + site.key()).header("Content-Type", "text/plain")
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":1,\"events\":[{\"type\":2,\"data\":{}}]}".formatted(session))
                .send();

        assertThat(compressed.statusCode()).isEqualTo(202);
        assertThat(raw.statusCode()).isEqualTo(202);
        List<ConsumerRecord<String, byte[]>> records = CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 2);
        assertThat(records).hasSize(2);
        List<ReplayEnvelope> envelopes = records.stream()
                .map(r -> WireJson.mapper().readValue(r.value(), ReplayEnvelope.class))
                .sorted(java.util.Comparator.comparingInt(ReplayEnvelope::chunkSeq))
                .toList();
        assertThat(envelopes).allSatisfy(e -> {
            assertThat(e.tenantId()).isEqualTo(site.tenantId());
            assertThat(e.siteId()).isEqualTo(site.siteId());
            assertThat(e.sessionId()).isEqualTo(session);
        });
        assertThat(envelopes.get(0).payload()).isEqualTo(payload);
        assertThat(envelopes.get(1).events().get(0).get("type").asInt()).isEqualTo(2);
    }

    /** Replay chunks may be up to 4 MB, far above the 1 MB telemetry limit and Kafka's 1 MB default. */
    @Test
    void chunkNearTheReplayLimitIsAccepted() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String payload = Base64.getEncoder().encodeToString(new byte[3 * 1024 * 1024 - 1024]);   // ~4 MB as base64

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"payload\":\"%s\"}".formatted(session, payload))
                .send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 1)).hasSize(1);
    }

    @Test
    void chunkOverTheReplayLimitIs413() {
        SiteFixture site = newSite();
        String payload = Base64.getEncoder().encodeToString(new byte[3 * 1024 * 1024 + 16]);

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"payload\":\"%s\"}".formatted(UUID.randomUUID(), payload))
                .send();

        assertThat(response.statusCode()).isEqualTo(413);
    }
}
