package io.sessioninsights.collector;

import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 5.0: the first requests after startup pin no virtual thread. Before, the first key
 * lookup created the HikariCP pool inside {@code synchronized} on a request's virtual thread.
 * JFR {@code jdk.VirtualThreadPinned} (no threshold) is recorded from before this class's own
 * Spring context starts.
 */
@TestPropertySource(properties = "collector.test.context=pinning")   // own context: startup is recorded
class VirtualThreadPinningTest extends CollectorIntegrationTest {

    private static final List<String> PINNED = new CopyOnWriteArrayList<>();
    private static final RecordingStream RECORDING = new RecordingStream();

    static {
        RECORDING.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        RECORDING.onEvent("jdk.VirtualThreadPinned", event -> PINNED.add(describe(event)));
        RECORDING.startAsync();
    }

    @AfterAll
    static void stopRecording() {
        RECORDING.close();
    }

    @Test
    void firstRequestsAfterStartupPinNoVirtualThread() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> first = post("/v1/events").key(site)
                .body(batchJson(session, eventJson(UUID.randomUUID(), now()))).send();
        HttpResponse<String> unknownKey = post("/v1/events?k=sk_test_unknown_" + UUID.randomUUID())
                .body(batchJson(session, eventJson(UUID.randomUUID(), now()))).send();
        HttpResponse<String> replay = post("/v1/replay").key(site)
                .body("{\"sessionId\":\"%s\",\"chunkSeq\":0,\"events\":[{\"type\":4,\"timestamp\":1}]}".formatted(session))
                .send();

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(unknownKey.statusCode()).isEqualTo(401);
        assertThat(replay.statusCode()).isEqualTo(202);
        RECORDING.stop();   // flushes pending events to the callback
        assertThat(PINNED).as("pinned virtual threads (JFR jdk.VirtualThreadPinned)").isEmpty();
    }

    private static String describe(RecordedEvent event) {
        List<RecordedFrame> frames = event.getStackTrace() == null ? List.of() : event.getStackTrace().getFrames();
        return event.getThread().getJavaName() + " pinned " + event.getDuration().toMillis() + " ms at\n  "
                + frames.stream().limit(15)
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName() + ":" + f.getLineNumber())
                .collect(Collectors.joining("\n  "));
    }
}
