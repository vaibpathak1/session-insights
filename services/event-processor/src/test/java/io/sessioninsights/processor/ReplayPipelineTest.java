package io.sessioninsights.processor;

import com.github.luben.zstd.Zstd;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.ReplayEnvelope;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.processor.store.ManifestRow;
import io.sessioninsights.processor.store.ReplayObjectStore;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ReplayPipelineTest extends ProcessorIntegrationTest {

    @Value("${processor.kafka.replay.group-id}")
    String replayGroup;

    @Test
    void chunksBecomeZstdObjectsAtTheTenantKeyAndManifestRows() {
        Session s = Session.random();
        long t0 = NOW.toEpochMilli();
        JsonNode first = Fixtures.rrwebEvents(t0, 3, true, "hello");
        JsonNode second = Fixtures.rrwebEvents(t0 + 1000, 4, false, null);
        ProcessorTestInfra.send(List.of(
                Fixtures.chunkRecord(s, Fixtures.chunk(s, 0, first)),
                Fixtures.chunkRecord(s, Fixtures.chunk(s, 1, second))));

        List<ManifestRow> manifest = await().atMost(Duration.ofSeconds(30))
                .until(() -> manifestStore.findChunks(s.tenantId(), s.sessionId()), m -> m.size() == 2);

        ManifestRow m0 = manifest.get(0);
        assertThat(m0.chunkSeq()).isZero();
        assertThat(m0.objectKey()).isEqualTo("tenants/" + s.tenantId() + "/sessions/" + s.sessionId() + "/000000.json.zst");
        assertThat(m0.tenantId()).isEqualTo(s.tenantId());
        assertThat(m0.siteId()).isEqualTo(s.siteId());
        assertThat(m0.eventCount()).isEqualTo(5);
        assertThat(m0.hasFullSnapshot()).isTrue();
        assertThat(m0.firstTs()).isEqualTo(Instant.ofEpochMilli(t0));
        assertThat(m0.lastTs()).isEqualTo(Instant.ofEpochMilli(t0 + 4));
        assertThat(m0.ingestedAt()).isEqualTo(NOW);
        assertThat(manifest.get(1).chunkSeq()).isEqualTo(1);
        assertThat(manifest.get(1).hasFullSnapshot()).isFalse();
        assertThat(manifest.get(1).objectKey()).endsWith("/000001.json.zst");

        byte[] object = objectStore.get(s.tenantId(), s.sessionId(), 0);
        assertThat(object).hasSize((int) m0.compressedBytes());
        assertThat(WireJson.mapper().readTree(decompress(object))).isEqualTo(first);
        assertThat(WireJson.mapper().readTree(decompress(objectStore.get(s.tenantId(), s.sessionId(), 1))))
                .isEqualTo(second);
        assertThat(ReplayObjectStore.objectKey(s.tenantId(), s.sessionId(), 1)).isEqualTo(manifest.get(1).objectKey());
    }

    @Test
    void redeliveredChunkOverwritesTheSameObjectAndIsOneManifestRowWithFinal() {
        Session s = Session.random();
        ReplayEnvelope chunk = Fixtures.chunk(s, 0, Fixtures.rrwebEvents(NOW.toEpochMilli(), 2, true, "again"));
        ProcessorTestInfra.send(List.of(Fixtures.chunkRecord(s, chunk)));
        await().atMost(Duration.ofSeconds(30)).until(() -> manifestStore.findChunks(s.tenantId(), s.sessionId()).size() == 1);
        byte[] before = objectStore.get(s.tenantId(), s.sessionId(), 0);

        RecordMetadata again = ProcessorTestInfra.send(List.of(Fixtures.chunkRecord(s, chunk))).getFirst();
        // the redelivery was processed (a background merge may already have collapsed the raw duplicate)
        TopicPartition partition = new TopicPartition(Topics.REPLAY_CHUNKS, again.partition());
        await().atMost(Duration.ofSeconds(30)).until(() ->
                ProcessorTestInfra.committedOffset(replayGroup, partition) > again.offset());

        assertThat(rawManifestRows(s)).isBetween(1L, 2L);
        assertThat(manifestStore.findChunks(s.tenantId(), s.sessionId())).hasSize(1);
        assertThat(objectStore.get(s.tenantId(), s.sessionId(), 0)).isEqualTo(before);
        assertThat(ProcessorTestInfra.S3.listObjectsV2(b -> b.bucket(ProcessorTestInfra.BUCKET)
                .prefix("tenants/" + s.tenantId() + "/")).keyCount()).isEqualTo(1);
    }

    /** Phase 4b: a ~10 MB full snapshot (the collector's limit is 16 MB) is stored intact. */
    @Test
    void largeFullSnapshotBecomesOneObjectThatDecompressesByteIdentical() {
        Session s = Session.random();
        JsonNode events = Fixtures.largeSnapshot(NOW.toEpochMilli(), 10 * 1024 * 1024);
        byte[] original = WireJson.mapper().writeValueAsBytes(events);
        assertThat(original.length).isGreaterThan(10 * 1024 * 1024 - 1024);
        ProcessorTestInfra.send(List.of(Fixtures.chunkRecord(s, Fixtures.chunk(s, 0, events))));

        ManifestRow m = await().atMost(Duration.ofSeconds(60))
                .until(() -> manifestStore.findChunks(s.tenantId(), s.sessionId()), rows -> rows.size() == 1)
                .getFirst();
        byte[] object = objectStore.get(s.tenantId(), s.sessionId(), 0);
        assertThat(decompress(object)).isEqualTo(original);
        assertThat(m.hasFullSnapshot()).isTrue();
        assertThat(m.eventCount()).isEqualTo(2);
        assertThat(m.compressedBytes()).isEqualTo(object.length).isLessThan(original.length / 10);
    }

    private static long rawManifestRows(Session s) {
        return CH.queryAll("SELECT count() AS c FROM replay_chunks WHERE tenant_id = {t:UUID} AND session_id = {s:UUID}",
                Map.of("t", s.tenantId(), "s", s.sessionId())).getFirst().getLong("c");
    }

    static byte[] decompress(byte[] zstd) {
        return Zstd.decompress(zstd, (int) Zstd.getFrameContentSize(zstd));
    }
}
