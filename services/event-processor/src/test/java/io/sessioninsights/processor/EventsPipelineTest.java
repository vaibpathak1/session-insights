package io.sessioninsights.processor;

import io.micrometer.core.instrument.MeterRegistry;
import io.sessioninsights.common.wire.TelemetryEvent;
import io.sessioninsights.processor.Fixtures.Session;
import io.sessioninsights.processor.store.EventRow;
import io.sessioninsights.common.Topics;
import io.sessioninsights.processor.ingest.EventsListener;
import io.sessioninsights.processor.ingest.ReplayListener;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.sessioninsights.processor.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class EventsPipelineTest extends ProcessorIntegrationTest {

    @Autowired
    MeterRegistry registry;

    @Autowired
    KafkaListenerEndpointRegistry listeners;

    @Value("${processor.kafka.events.group-id}")
    String eventsGroup;

    @Test
    void batchOfEventsBecomesRowsWithTheV1Columns() {
        Session a = Session.random();
        List<TelemetryEvent> sent = new ArrayList<>();
        List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            TelemetryEvent event = Fixtures.click(NOW.toEpochMilli() + i, "Pay now", "{\"button\":\"pay\",\"n\":" + i + "}");
            sent.add(event);
            records.add(Fixtures.eventRecord(a, event));
        }
        ProcessorTestInfra.send(records);

        List<EventRow> rows = await().atMost(Duration.ofSeconds(30))
                .until(() -> eventStore.findEvents(a.tenantId(), a.sessionId()), r -> r.size() == 5);

        for (int i = 0; i < 5; i++) {
            EventRow row = rows.get(i);
            TelemetryEvent event = sent.get(i);
            assertThat(row.eventId()).isEqualTo(event.clientEventId());
            assertThat(row.tenantId()).isEqualTo(a.tenantId());
            assertThat(row.siteId()).isEqualTo(a.siteId());
            assertThat(row.sessionId()).isEqualTo(a.sessionId());
            assertThat(row.anonymousId()).isEqualTo("anon-1");
            assertThat(row.endUserId()).isNull();
            assertThat(row.eventType()).isEqualTo("CLICK");
            assertThat(row.ts()).isEqualTo(Instant.ofEpochMilli(event.ts()));
            assertThat(row.ingestedAt()).isEqualTo(NOW);
            assertThat(row.url()).isEqualTo(event.url());
            assertThat(row.path()).isEqualTo("/cart");
            assertThat(row.pageTitle()).isEqualTo("Cart");
            assertThat(row.targetSelector()).isEqualTo("button#pay");
            assertThat(row.targetText()).isEqualTo("Pay now");
            assertThat(row.errorMessage()).isEmpty();
            assertThat(row.eventName()).isEmpty();
            assertThat(row.props().path("button").asString()).isEqualTo("pay");
            assertThat(row.props().path("n").asInt()).isEqualTo(i);
        }
    }

    @Test
    void redeliveredRecordsAreOneRowEachWithFinal() {
        Session s = Session.random();
        List<ProducerRecord<String, byte[]>> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            records.add(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli() + i, "x", null)));
        }
        ProcessorTestInfra.send(records);
        List<RecordMetadata> again = ProcessorTestInfra.send(records.stream()   // at-least-once: same values, new offsets
                .map(r -> new ProducerRecord<>(r.topic(), r.key(), r.value())).toList());

        // both deliveries written (background merges may already have collapsed the raw duplicates)
        TopicPartition partition = new TopicPartition(Topics.TELEMETRY_EVENTS, again.getLast().partition());
        await().atMost(Duration.ofSeconds(30)).until(() ->
                ProcessorTestInfra.committedOffset(eventsGroup, partition) > again.getLast().offset());
        assertThat(rawEventRows(s.tenantId(), s.sessionId())).isBetween(3L, 6L);
        assertThat(finalEventRows(s.tenantId(), s.sessionId())).isEqualTo(3);
        assertThat(eventStore.findEvents(s.tenantId(), s.sessionId())).hasSize(3);
    }

    @Test
    void readsNeverCrossTenants() {
        Session a = Session.random();
        Session otherTenant = Session.random();
        Session sameSessionOtherTenant = new Session(otherTenant.tenantId(), otherTenant.siteId(), a.sessionId());
        ProcessorTestInfra.send(List.of(
                Fixtures.eventRecord(a, Fixtures.click(NOW.toEpochMilli(), "a", null)),
                Fixtures.eventRecord(sameSessionOtherTenant, Fixtures.click(NOW.toEpochMilli(), "b", null)),
                Fixtures.eventRecord(sameSessionOtherTenant, Fixtures.click(NOW.toEpochMilli() + 1, "b", null))));

        await().atMost(Duration.ofSeconds(30)).until(() ->
                eventStore.findEvents(sameSessionOtherTenant.tenantId(), a.sessionId()).size() == 2);
        assertThat(eventStore.findEvents(a.tenantId(), a.sessionId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.tenantId()).isEqualTo(a.tenantId());
                    assertThat(r.targetText()).isEqualTo("a");
                });
        assertThat(eventStore.findEvents(UUID.randomUUID(), a.sessionId())).isEmpty();
    }

    @Test
    void publishesProcessorAndConsumerLagMetrics() {
        Session s = Session.random();
        ProcessorTestInfra.send(List.of(Fixtures.eventRecord(s, Fixtures.click(NOW.toEpochMilli(), "m", null))));
        await().atMost(Duration.ofSeconds(30)).until(() -> finalEventRows(s.tenantId(), s.sessionId()) == 1);

        assertThat(registry.get(ProcessorMetrics.ROWS_INSERTED).tag("table", "events").counter().count()).isPositive();
        assertThat(registry.get(ProcessorMetrics.INSERT_LATENCY).tag("table", "events").tag("outcome", "success")
                .timer().count()).isPositive();
        assertThat(registry.get(ProcessorMetrics.BATCH_SIZE).tag("listener", "events").tag("topic", "telemetry.events.v1").summary().count())
                .isPositive();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(registry.find("kafka.consumer.fetch.manager.records.lag.max").meters()).isNotEmpty());
    }

    @Test
    void consumersRunOnPlatformThreadsOnePerPartition() throws Exception {
        for (String id : List.of(EventsListener.ID, ReplayListener.ID)) {
            var container = (ConcurrentMessageListenerContainer<?, ?>) listeners.getListenerContainer(id);
            assertThat(container.getConcurrency()).isEqualTo(ProcessorTestInfra.PARTITIONS);
            for (var child : container.getContainers()) {
                var executor = child.getContainerProperties().getListenerTaskExecutor();
                assertThat(executor.submit(() -> Thread.currentThread().isVirtual()).get())
                        .as("kafka-clients pins virtual threads on Java 21 (ADR-0011)").isFalse();
            }
        }
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName)
                .filteredOn(name -> name.startsWith("kafka-consumer-")).hasSizeGreaterThanOrEqualTo(2 * ProcessorTestInfra.PARTITIONS);
    }
}
