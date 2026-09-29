package io.sessioninsights.collector.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorMetrics;
import io.sessioninsights.collector.CollectorTestInfra;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import io.sessioninsights.common.Topics;
import io.sessioninsights.common.wire.EventType;
import io.sessioninsights.common.wire.TelemetryEnvelope;
import io.sessioninsights.common.wire.WireHeaders;
import io.sessioninsights.common.wire.WireJson;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class EventsEndpointTest extends CollectorIntegrationTest {

    @Autowired
    MeterRegistry meters;

    @Test
    void acceptedBatchLandsOnTelemetryTopicKeyedBySessionWithServerAssignedTenant() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID forgedTenant = UUID.randomUUID();
        String body = """
                {"sessionId":"%s","anonymousId":"anon-1","sdkVersion":"0.1.0","tenantId":"%s","siteId":"%s",
                 "events":[%s,%s]}""".formatted(session, forgedTenant, forgedTenant, eventJson(e1, now()), eventJson(e2, now()));
        Instant before = Instant.now();

        HttpResponse<String> response = post("/v1/events").key(site).body(body).send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"accepted\":2,\"dropped\":0}");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        assertThat(response.headers().firstValue("Vary")).hasValue("Origin");

        List<ConsumerRecord<String, byte[]>> records = CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 2);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(r -> envelope(r).event().clientEventId()).containsExactlyInAnyOrder(e1, e2);
        for (ConsumerRecord<String, byte[]> record : records) {
            TelemetryEnvelope envelope = envelope(record);
            assertThat(envelope.tenantId()).isEqualTo(site.tenantId()).isNotEqualTo(forgedTenant);
            assertThat(envelope.siteId()).isEqualTo(site.siteId());
            assertThat(envelope.sessionId()).isEqualTo(session);
            assertThat(envelope.schemaVersion()).isEqualTo(1);
            assertThat(envelope.anonymousId()).isEqualTo("anon-1");
            assertThat(envelope.receivedAt()).isBetween(before, Instant.now());
            assertThat(envelope.event().type()).isEqualTo(EventType.CLICK);
            assertThat(header(record, WireHeaders.TENANT_ID)).isEqualTo(site.tenantId().toString());
            assertThat(header(record, WireHeaders.SCHEMA_VERSION)).isEqualTo("1");
        }
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
    }

    @Test
    void userAgentIsCarriedInTheEnvelopeTruncatedTo512() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String chrome = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

        post("/v1/events").key(site).header("User-Agent", chrome).body(batchJson(session, eventJson(UUID.randomUUID(), now()))).send();
        post("/v1/events").key(site).header("User-Agent", "x".repeat(2000)).body(batchJson(other, eventJson(UUID.randomUUID(), now()))).send();

        assertThat(envelope(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1).getFirst()).userAgent())
                .isEqualTo(chrome);
        assertThat(envelope(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, other.toString(), 1).getFirst()).userAgent())
                .hasSize(512);
    }

    @Test
    void beaconPathTextPlainWithKeyInQuery() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> response = post("/v1/events?k=" + site.key())
                .header("Content-Type", "text/plain;charset=UTF-8")
                .body(batchJson(session, eventJson(UUID.randomUUID(), now())))
                .send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1)).hasSize(1);
    }

    @Test
    void keyInBodyIsAcceptedAsLastResort() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String body = """
                {"siteKey":"%s","sessionId":"%s","events":[%s]}""".formatted(site.key(), session, eventJson(UUID.randomUUID(), now()));

        assertThat(post("/v1/events").body(body).send().statusCode()).isEqualTo(202);
    }

    @Test
    void gzipBody() throws IOException {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> response = post("/v1/events").key(site)
                .header("Content-Encoding", "gzip")
                .body(gzip(batchJson(session, eventJson(UUID.randomUUID(), now())).getBytes(StandardCharsets.UTF_8)))
                .send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1)).hasSize(1);
    }

    @Test
    void unknownOrMissingKeyIs401ReadableByBrowsers() {
        String body = batchJson(UUID.randomUUID(), eventJson(UUID.randomUUID(), now()));

        HttpResponse<String> unknown = post("/v1/events?k=sk_test_nope").body(body).send();
        HttpResponse<String> missing = post("/v1/events").body(body).send();

        // ADR-0012: the browser can read the refusal, so the SDK stops on the first one
        for (HttpResponse<String> response : List.of(unknown, missing)) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}");
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
            assertThat(response.headers().firstValue("Vary")).hasValue("Origin");
            assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        }
        // a non-browser client sends no Origin: nothing to echo
        HttpResponse<String> server = post("/v1/events").header("Origin", null).header("X-SI-Key", "sk_test_nope")
                .body(body).send();
        assertThat(server.statusCode()).isEqualTo(401);
        assertThat(server.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void originNotAllowedOrMissingIs403ReadableByTheRequestingOrigin() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String body = batchJson(session, eventJson(UUID.randomUUID(), now()));

        HttpResponse<String> evil = post("/v1/events").key(site).header("Origin", "https://evil.example").body(body).send();
        HttpResponse<String> lookalike = post("/v1/events").key(site).header("Origin", "http://localhost.evil.example:3000").body(body).send();
        HttpResponse<String> noOrigin = post("/v1/events").key(site).header("Origin", null).body(body).send();
        HttpResponse<String> nullOrigin = post("/v1/events").key(site).header("Origin", "null").body(body).send();

        for (HttpResponse<String> response : List.of(evil, lookalike, noOrigin, nullOrigin)) {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).isEqualTo("{\"error\":\"forbidden\"}");
            assertThat(response.headers().firstValue("Vary")).hasValue("Origin");
        }
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://evil.example");
        assertThat(lookalike.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost.evil.example:3000");
        assertThat(noOrigin.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(nullOrigin.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        // the allow-list is still enforced on the POST: nothing was produced
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 0)).isEmpty();
    }

    @Test
    void preflightAlwaysSucceedsAndEchoesTheOrigin() {
        SiteFixture site = newSite();

        for (var preflight : List.of(
                options("/v1/events?k=" + site.key()).header("Origin", "http://localhost:5173"),   // allowed
                options("/v1/events?k=" + site.key()).header("Origin", "https://evil.example"),     // not allowed
                options("/v1/replay?k=sk_test_unknown").header("Origin", "https://evil.example"),   // unknown key
                options("/v1/events").header("Origin", "https://evil.example"))) {                  // no key
            HttpResponse<String> response = preflight
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type,content-encoding")
                    .send();
            String origin = response.request().headers().firstValue("Origin").orElseThrow();
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(origin);
            assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).hasValue("POST");
            assertThat(response.headers().firstValue("Access-Control-Allow-Headers")).hasValue("Content-Type, Content-Encoding, X-SI-Key");
            assertThat(response.headers().firstValue("Access-Control-Max-Age")).hasValue("600");
            assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
            assertThat(response.headers().firstValue("Vary")).hasValue("Origin");
        }
        for (String origin : new String[] {null, "null"}) {
            HttpResponse<String> response = options("/v1/events").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST").send();
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        }
    }

    @Test
    void oversizeBodyIs413() {
        SiteFixture site = newSite();
        String padding = "x".repeat(1024 * 1024);
        String body = batchJson(UUID.randomUUID(), eventJson(UUID.randomUUID(), now())).replace("\"anon-1\"", "\"" + padding + "\"");

        HttpResponse<String> response = post("/v1/events").key(site).body(body).send();

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).isEqualTo("{\"error\":\"too_large\"}");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).as("readable by the SDK").hasValue(ORIGIN);
    }

    @Test
    void gzipBombIs413() throws IOException {
        SiteFixture site = newSite();
        byte[] bomb = gzip(new byte[32 * 1024 * 1024]);

        HttpResponse<String> response = post("/v1/events").key(site).header("Content-Encoding", "gzip").body(bomb).send();

        assertThat(bomb.length).isLessThan(64 * 1024);
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void invalidUuidIs400AndNothingIsProduced() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();

        HttpResponse<String> badSession = post("/v1/events").key(site)
                .body(batchJson(UUID.randomUUID(), eventJson(UUID.randomUUID(), now())).replaceFirst("\"sessionId\":\"[^\"]+\"", "\"sessionId\":\"123\""))
                .send();
        HttpResponse<String> badEventId = post("/v1/events").key(site)
                .body(batchJson(session, eventJson(UUID.randomUUID(), now()), eventJson(UUID.randomUUID(), now()).replaceFirst("\"clientEventId\":\"[^\"]+\"", "\"clientEventId\":\"e-1\"")))
                .send();

        assertThat(badSession.statusCode()).isEqualTo(400);
        assertThat(badSession.body()).isEqualTo("{\"error\":\"invalid\"}");
        assertThat(badEventId.statusCode()).isEqualTo(400);
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 0)).isEmpty();
    }

    @Test
    void unknownEventTypeIsDroppedAndCountedWhileTheRestIsAccepted() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        UUID known = UUID.randomUUID();
        double droppedBefore = dropped(CollectorMetrics.DROPPED_UNKNOWN_TYPE);

        HttpResponse<String> response = post("/v1/events").key(site)
                .body(batchJson(session, eventJson(known, now()), eventJson(UUID.randomUUID(), now()).replace("CLICK", "TELEPORT")))
                .send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"accepted\":1,\"dropped\":1}");
        assertThat(dropped(CollectorMetrics.DROPPED_UNKNOWN_TYPE) - droppedBefore).isEqualTo(1.0);
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1))
                .extracting(r -> envelope(r).event().clientEventId()).containsExactly(known);
    }

    @Test
    void unsupportedContentTypeIs415() {
        SiteFixture site = newSite();

        HttpResponse<String> response = post("/v1/events").key(site).header("Content-Type", "application/x-www-form-urlencoded")
                .body("a=b").send();

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void outOfWindowTimestampsAreDroppedAndCounted() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        double droppedBefore = dropped(CollectorMetrics.DROPPED_TS_OUT_OF_WINDOW);

        HttpResponse<String> response = post("/v1/events").key(site).body(batchJson(session,
                eventJson(fresh, now()),
                eventJson(UUID.randomUUID(), now() - Duration.ofHours(25).toMillis()),
                eventJson(UUID.randomUUID(), now() + Duration.ofMinutes(10).toMillis()))).send();

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEqualTo("{\"accepted\":1,\"dropped\":2}");
        assertThat(dropped(CollectorMetrics.DROPPED_TS_OUT_OF_WINDOW) - droppedBefore).isEqualTo(2.0);
        assertThat(CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1))
                .extracting(r -> envelope(r).event().clientEventId()).containsExactly(fresh);
    }

    @Test
    void emailAndCardNumberAreRedactedBeforeKafka() {
        SiteFixture site = newSite();
        UUID session = UUID.randomUUID();
        String event = """
                {"clientEventId":"%s","type":"ERROR_CLICK","ts":%d,
                 "url":"http://localhost:3000/pay?email=jane.doe%%40example.com",
                 "targetText":"Receipt for jane.doe@example.com",
                 "errorMessage":"card 4111 1111 1111 1111 declined"}""".formatted(UUID.randomUUID(), now());

        assertThat(post("/v1/events").key(site).body(batchJson(session, event)).send().statusCode()).isEqualTo(202);

        ConsumerRecord<String, byte[]> record = CollectorTestInfra.records(Topics.TELEMETRY_EVENTS, session.toString(), 1).getFirst();
        String raw = new String(record.value(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain("jane").doesNotContain("4111");
        var e = envelope(record).event();
        assertThat(e.targetText()).isEqualTo("Receipt for [email]");
        assertThat(e.errorMessage()).isEqualTo("card [card] declined");
        assertThat(e.url()).isEqualTo("http://localhost:3000/pay?email=%5Bemail%5D");
    }

    private double dropped(String reason) {
        var counter = meters.find(CollectorMetrics.EVENTS_DROPPED).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    static TelemetryEnvelope envelope(ConsumerRecord<String, byte[]> record) {
        return WireJson.mapper().readValue(record.value(), TelemetryEnvelope.class);
    }

    static String header(ConsumerRecord<String, byte[]> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    static byte[] gzip(byte[] data) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
