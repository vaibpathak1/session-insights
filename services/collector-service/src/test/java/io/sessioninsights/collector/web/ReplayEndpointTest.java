package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorTestInfra;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    /**
     * Phase 4b: a large full snapshot (~10 MB of DOM JSON) is accepted. It is far over the
     * topic's 4 MB max.message.bytes uncompressed, but fits once the producer compresses it.
     */
    @Test
    void largeCompressibleSnapshotIsAcceptedAndProduced() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String body = snapshotJson(session, 10 * 1024 * 1024);

        HttpResponse<String> response = post("/v1/replay").key(site).body(body).send();

        assertThat(response.statusCode()).isEqualTo(202);
        List<ConsumerRecord<String, byte[]>> records = CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 1);
        assertThat(records).hasSize(1);
        ReplayEnvelope envelope = WireJson.mapper().readValue(records.getFirst().value(), ReplayEnvelope.class);
        assertThat(envelope.events()).isEqualTo(WireJson.mapper().readTree(body).get("events"));
        assertThat(records.getFirst().serializedValueSize()).isGreaterThan(10 * 1024 * 1024);
    }

    /** Within the 16 MB body limit, but incompressible: the compressed record cannot fit the topic → 413. */
    @Test
    void incompressibleChunkThatCannotFitTheTopicIs413AndNothingIsProduced() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> response = post("/v1/replay").key(site)
                .body(chunkJson(session, 16 * 1024 * 1024 - 1024))
                .send();

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"error\":\"too_large\"}");
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 0)).isEmpty();
    }

    /** An incompressible chunk that does fit after compression is still fine (~3 MB). */
    @Test
    void incompressibleChunkThatFitsIsAccepted() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> response = post("/v1/replay").key(site).body(chunkJson(session, 3 * 1024 * 1024)).send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 1)).hasSize(1);
    }

    @Test
    void bodyOverSixteenMegabytesDecompressedIs413() throws IOException {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        byte[] json = snapshotJson(session, 16 * 1024 * 1024 + 4096).getBytes(StandardCharsets.UTF_8);
        assertThat(json.length).isGreaterThan(16 * 1024 * 1024);

        HttpResponse<String> plain = post("/v1/replay").key(site).body(json).send();
        HttpResponse<String> gzipped = post("/v1/replay").key(site).header("Content-Encoding", "gzip").body(gzip(json)).send();

        assertThat(plain.statusCode()).isEqualTo(413);
        assertThat(gzipped.statusCode()).isEqualTo(413);   // measured after decompression
        assertThat(CollectorTestInfra.records(Topics.REPLAY_CHUNKS, session.toString(), 0)).isEmpty();
    }

    /** A chunk-0 ReplayBatch of about {@code bytes} bytes: Meta + a FullSnapshot of repetitive DOM JSON. */
    static String snapshotJson(UUID session, int bytes) {
        String head = "{\"sessionId\":\"%s\",\"chunkSeq\":0,\"events\":[{\"type\":4,\"timestamp\":1,\"data\":{\"href\":\"http://localhost/\"}},{\"type\":2,\"timestamp\":2,\"data\":{\"node\":{\"type\":0,\"childNodes\":[".formatted(session);
        String tail = "{\"type\":3,\"textContent\":\"end\",\"id\":0}]}}}]}";
        StringBuilder json = new StringBuilder(bytes + 256).append(head);
        int id = 1;
        while (json.length() + tail.length() < bytes - 120) {
            json.append("{\"type\":2,\"tagName\":\"td\",\"attributes\":{\"class\":\"cell\"},\"childNodes\":[{\"type\":3,\"textContent\":\"row ")
                    .append(id % 997).append("\",\"id\":").append(id + 1).append("}],\"id\":").append(id).append("},");
            id += 2;
        }
        return json.append(tail).toString();
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

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(bytes);
        }
        return out.toByteArray();
    }
}
