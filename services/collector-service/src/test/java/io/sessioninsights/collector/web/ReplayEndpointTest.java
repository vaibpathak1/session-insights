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
    void rawChunksLandOnReplayTopicWithServerAssignedTenant() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> json = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"events\":[{\"type\":4,\"data\":{}}],\"tenantId\":\"%s\"}"
                        .formatted(session, UUID.randomUUID()))
                .send();
        HttpResponse<String> beacon = post("/v1/replay?k=" + site.key()).header("Content-Type", "text/plain")
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":1,\"events\":[{\"type\":2,\"data\":{}}]}".formatted(session))
                .send();

        assertThat(json.statusCode()).isEqualTo(202);
        assertThat(beacon.statusCode()).isEqualTo(202);
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
            assertThat(e.payload()).isNull();
        });
        assertThat(envelopes.get(0).events().get(0).get("type").asInt()).isEqualTo(4);
        assertThat(envelopes.get(1).events().get(0).get("type").asInt()).isEqualTo(2);
    }

    /** Phase 4b: the base64 "payload" form is no longer part of the contract. */
    @Test
    void base64PayloadFormIs400AndNothingIsProduced() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String payload = Base64.getEncoder().encodeToString("compressed-rrweb".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"payload\":\"%s\"}".formatted(session, payload))
                .send();

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).isEqualTo("{\"error\":\"invalid\"}");
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 0)).isEmpty();
    }

    /** Replay chunks may be up to 4 MB, far above the 1 MB telemetry limit and Kafka's 1 MB default. */
    @Test
    void chunkNearTheReplayLimitIsAccepted() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body(chunkJson(session, 4 * 1024 * 1024 - 1024))
                .send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 1)).hasSize(1);
    }

    @Test
    void chunkOverTheReplayLimitIs413() {
        SiteFixture site = newSite();

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body(chunkJson(UUID.randomUUID(), 4 * 1024 * 1024 + 16))
                .send();

        assertThat(response.statusCode()).isEqualTo(413);
    }

    /** A ReplayBatch of about {@code bytes} bytes: one rrweb event padded with random base64 text. */
    static String chunkJson(UUID session, int bytes) {
        String head = "{\"sessionId\":\"%s\",\"chunkSeq\":0,\"events\":[{\"type\":2,\"timestamp\":1,\"data\":\"".formatted(session);
        String tail = "\"}]}";
        byte[] random = new byte[(bytes - head.length() - tail.length()) * 3 / 4 + 3];
        new java.util.Random(42).nextBytes(random);
        String padding = Base64.getEncoder().encodeToString(random).substring(0, bytes - head.length() - tail.length());
        return head + padding + tail;
    }
}
