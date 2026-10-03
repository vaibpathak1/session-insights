package io.sessioninsights.processor.ingest;

import com.github.luben.zstd.Zstd;
import io.sessioninsights.common.wire.WireJson;
import io.sessioninsights.processor.Fixtures;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.events.ManifestRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayChunkTest {

    @Test
    void compressesTheEventArrayAndDerivesTheManifestRow() {
        Session s = Session.random();
        // out-of-order timestamps: first/last are min/max, not array ends
        JsonNode events = WireJson.mapper().readTree(
                "[{\"type\":3,\"timestamp\":1500},{\"type\":2,\"timestamp\":1000},{\"type\":3,\"timestamp\":2000}]");
        ReplayChunk chunk = ReplayChunk.of(Fixtures.chunk(s, 7, events), 3);

        byte[] json = Zstd.decompress(chunk.compressed(), (int) Zstd.getFrameContentSize(chunk.compressed()));
        assertThat(WireJson.mapper().readTree(json)).isEqualTo(events);
        ManifestRow m = chunk.manifest();
        assertThat(m.objectKey()).isEqualTo("tenants/" + s.tenantId() + "/sessions/" + s.sessionId() + "/000007.json.zst");
        assertThat(m.chunkSeq()).isEqualTo(7);
        assertThat(m.eventCount()).isEqualTo(3);
        assertThat(m.firstTs()).isEqualTo(Instant.ofEpochMilli(1000));
        assertThat(m.lastTs()).isEqualTo(Instant.ofEpochMilli(2000));
        assertThat(m.hasFullSnapshot()).isTrue();
        assertThat(m.compressedBytes()).isEqualTo(chunk.compressed().length);
        assertThat(m.ingestedAt()).isEqualTo(Fixtures.NOW);
        assertThat(ReplayChunk.of(Fixtures.chunk(s, 8, Fixtures.rrwebEvents(1, 2, false, null)), 3)
                .manifest().hasFullSnapshot()).isFalse();
    }
}
