package io.sessioninsights.processor;

import io.sessioninsights.processor.Fixtures.Session;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingStream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * No virtual thread is pinned while blocking, anywhere in the processor: consumer startup and
 * group joins, ClickHouse inserts, and parallel S3 PUTs (ADR-0011). Uses JFR's
 * {@code jdk.VirtualThreadPinned} with no threshold, recorded from before this class's own
 * Spring context starts, across a run that touches every partition of both topics.
 */
@TestPropertySource(properties = "processor.test.context=pinning")   // own context: startup is recorded
class VirtualThreadPinningTest extends ProcessorIntegrationTest {

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
    void noVirtualThreadIsPinnedAcrossConsumersClickHouseAndS3() {
        List<Session> sessions = new ArrayList<>();
        List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 30; i++) {   // 30 keys: every partition of both topics
            Session s = Session.random();
            sessions.add(s);
            for (int e = 0; e < 10; e++) {
                records.add(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + e, "pin", null)));
            }
            for (int seq = 0; seq < 4; seq++) {
                records.add(Fixtures.chunkRecord(s, Fixtures.chunk(s, seq,
                        Fixtures.rrwebEvents(NOW.toEpochMilli() + seq * 100L, 20, seq == 0, "pin"))));
            }
        }
        ProcessorTestInfra.send(records);

        await().atMost(Duration.ofSeconds(60)).until(() -> sessions.stream().allMatch(s ->
                finalEventRows(s.tenantId(), s.sessionId()) == 10
                        && manifestReader.findChunks(s.tenantId(), s.sessionId()).size() == 4));

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
