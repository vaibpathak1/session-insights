package io.sessioninsights.api.sessions;

import com.clickhouse.client.api.Client;
import io.sessioninsights.api.ApiIntegrationTest;
import io.sessioninsights.api.sessions.ApiFixtures.Tenant;
import io.sessioninsights.common.wire.WireJson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Tasks 5.6 and 5.7 end to end over HTTP: authentication, tenant binding, the read API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class SessionApiTest extends ApiIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    // relative to now: ClickHouse events expire 30 days after ts (TTL), so a fixed date is a time bomb
    private static final Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.DAYS);
    private static final String SNAPSHOT = "[{\"type\":4,\"timestamp\":1,\"data\":{\"href\":\"http://localhost/\"}},"
            + "{\"type\":2,\"timestamp\":2,\"data\":{\"node\":{\"type\":0,\"childNodes\":[]}}}]";

    @LocalServerPort
    int port;

    @Autowired
    Client clickhouse;

    ApiFixtures fixtures;
    Tenant a;
    Tenant b;
    UUID closedWithErrors;
    UUID openSession;
    UUID otherTenantsSession;

    @BeforeAll
    void seed() {
        fixtures = new ApiFixtures(new JdbcTemplate(new SingleConnectionDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), true)), clickhouse, S3, BUCKET);
        a = fixtures.tenant("a");
        b = fixtures.tenant("b");
        closedWithErrors = fixtures.session(a, T0, T0.plusSeconds(90), "http://localhost:5173/checkout?step=1", 2, 90_000,
                "{\"plan\":\"pro\",\"email\":\"jane.doe@example.com\",\"tags\":[\"call 555-123-4567\"]}");
        for (int i = 0; i < 5; i++) {
            fixtures.session(a, T0.plus(i + 1, ChronoUnit.MINUTES), T0.plus(i + 1, ChronoUnit.MINUTES).plusSeconds(10),
                    "http://localhost:5173/page-" + i + "_x%", 0, 10_000, "{}");
        }
        openSession = fixtures.session(a, T0.plus(1, ChronoUnit.HOURS), null, "http://localhost:5173/", 0, 0, "{}");
        otherTenantsSession = fixtures.session(b, T0, T0.plusSeconds(5), "http://localhost:5173/secret-b", 1, 5_000, "{}");
        for (int i = 0; i < 7; i++) {
            fixtures.event(a, closedWithErrors, T0.plusSeconds(i / 2), i % 3 == 0 ? "NAVIGATION" : "CLICK", "click " + i);
        }
        fixtures.event(a, openSession, T0.plus(1, ChronoUnit.HOURS), "CLICK", "live");
        fixtures.chunk(a, closedWithErrors, 0, SNAPSHOT, true);
        fixtures.chunk(a, closedWithErrors, 1, "[{\"type\":3,\"timestamp\":3,\"data\":{}}]", false);
        fixtures.chunk(a, openSession, 0, SNAPSHOT, true);
        fixtures.chunk(b, otherTenantsSession, 0, SNAPSHOT, true);
    }

    // ------------------------------------------------------------------ 5.6 authentication and tenants

    @Test
    void unauthenticatedAndBadCredentialsAre401HealthIsOpen() {
        assertThat(get("/api/v1/sessions", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/sessions", basic(a.email(), "wrong-password-xyz")).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/sessions", basic("nobody@example.com", DEV_PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/sessions/" + closedWithErrors + "/replay/0", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anotherTenantsSessionIs404NotForbidden() {
        for (String path : List.of("", "/events", "/replay", "/replay/0")) {
            HttpResponse<String> response = get("/api/v1/sessions/" + otherTenantsSession + path, asA());
            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).isEqualTo("{\"error\":\"not_found\"}");
        }
        assertThat(get("/api/v1/sessions/" + UUID.randomUUID(), asA()).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sessions/" + otherTenantsSession, asB()).statusCode()).isEqualTo(200);
    }

    @Test
    void theListOnlyEverShowsTheCallersTenant() {
        List<String> ids = allSessionIds(asA(), "");
        assertThat(ids).hasSize(7).doesNotContain(otherTenantsSession.toString());
        assertThat(allSessionIds(asB(), "")).containsExactly(otherTenantsSession.toString());
    }

    // ------------------------------------------------------------------ 5.7 list

    @Test
    void listFieldsAndFilters() {
        JsonNode first = json(get("/api/v1/sessions?hasError=true", asA())).path("items");
        assertThat(first).hasSize(1);
        JsonNode s = first.get(0);
        assertThat(s.path("id").asString()).isEqualTo(closedWithErrors.toString());
        assertThat(s.path("status").asString()).isEqualTo("closed");
        assertThat(s.path("durationMs").asLong()).isEqualTo(90_000);
        assertThat(s.path("pageCount").asInt()).isEqualTo(2);
        assertThat(s.path("errorCount").asInt()).isEqualTo(2);
        assertThat(s.path("platform").asString()).isEqualTo("desktop");
        assertThat(s.path("browser").asString()).isEqualTo("Chrome 140");
        assertThat(s.path("analysisStatus").asString()).isEqualTo("PENDING");
        assertThat(s.path("frictionScore").isNull()).isTrue();
        assertThat(s.path("user").path("anonymousId").asString()).isEqualTo("anon-" + closedWithErrors);

        assertThat(allSessionIds(asA(), "&status=open")).containsExactly(openSession.toString());
        assertThat(allSessionIds(asA(), "&status=closed")).hasSize(6);
        assertThat(allSessionIds(asA(), "&url=checkout")).containsExactly(closedWithErrors.toString());
        assertThat(allSessionIds(asA(), "&url=" + enc("_x%"))).as("LIKE wildcards are literal").hasSize(5);
        assertThat(allSessionIds(asA(), "&minDurationMs=50000")).containsExactly(closedWithErrors.toString());
        assertThat(allSessionIds(asA(), "&maxDurationMs=10000")).hasSize(6);
        assertThat(allSessionIds(asA(), "&from=" + T0.plus(2, ChronoUnit.MINUTES) + "&to=" + T0.plus(4, ChronoUnit.MINUTES)))
                .hasSize(2);
        assertThat(get("/api/v1/sessions?status=gone", asA()).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/sessions?limit=0", asA()).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/sessions?cursor=nonsense", asA()).body()).contains("\"parameter\":\"cursor\"");
    }

    @Test
    void keysetPaginationIsStableWhileNewSessionsArrive() {
        JsonNode page1 = json(get("/api/v1/sessions?limit=3", asA()));
        List<String> seen = new ArrayList<>(ids(page1));
        // a new session starts after page 1 was read; it is newer than everything, so it must
        // neither appear in later pages nor shift them
        Tenant sameTenant = a;
        UUID newer = fixtures.session(sameTenant, T0.plus(2, ChronoUnit.HOURS), null, "http://localhost/new", 0, 0, "{}");
        String cursor = page1.path("nextCursor").asString();
        while (cursor != null && !cursor.isEmpty()) {
            JsonNode page = json(get("/api/v1/sessions?limit=3&cursor=" + cursor, asA()));
            seen.addAll(ids(page));
            JsonNode next = page.path("nextCursor");
            cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
        }
        assertThat(seen).hasSize(7).doesNotHaveDuplicates().doesNotContain(newer.toString());
        assertThat(allSessionIds(asA(), "")).first().isEqualTo(newer.toString());
        TestCleanup.deleteSession(POSTGRES, newer);
    }

    // ------------------------------------------------------------------ 5.7 detail, events, replay

    @Test
    void detailCarriesRedactedTraits() {
        JsonNode detail = json(get("/api/v1/sessions/" + closedWithErrors, asA()));
        assertThat(detail.path("session").path("id").asString()).isEqualTo(closedWithErrors.toString());
        assertThat(detail.path("siteId").asString()).isEqualTo(a.siteId().toString());
        JsonNode traits = detail.path("userTraits");
        assertThat(traits.path("plan").asString()).isEqualTo("pro");
        assertThat(traits.path("email").asString()).isEqualTo("[email]");
        assertThat(traits.path("tags").get(0).asString()).isEqualTo("call [phone]");
        assertThat(detail.toString()).doesNotContain("jane.doe@example.com").doesNotContain("555-123-4567");
    }

    @Test
    void eventsComeInOrderAndPageWithACursor() {
        List<JsonNode> all = new ArrayList<>();
        String after = null;
        do {
            JsonNode page = json(get("/api/v1/sessions/" + closedWithErrors + "/events?limit=3"
                    + (after == null ? "" : "&after=" + after), asA()));
            page.path("items").forEach(all::add);
            JsonNode next = page.path("nextCursor");
            after = next.isNull() || next.isMissingNode() ? null : next.asString();
        } while (after != null);
        assertThat(all).hasSize(7);
        for (int i = 1; i < all.size(); i++) {
            assertThat(Instant.parse(all.get(i - 1).path("ts").asString()))
                    .isBeforeOrEqualTo(Instant.parse(all.get(i).path("ts").asString()));
        }
        assertThat(all).extracting(e -> e.path("id").asString()).doesNotHaveDuplicates();
        assertThat(all).filteredOn(e -> e.path("type").asString().equals("NAVIGATION")).hasSize(3);
        assertThat(all.getFirst().path("props").path("k").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/sessions/" + closedWithErrors + "/events?after=%21", asA()).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/sessions/" + closedWithErrors + "/events?limit=5000", asA()).statusCode()).isEqualTo(400);
    }

    @Test
    void manifestListsChunksInOrderAndChunksStreamDecompressed() throws IOException, InterruptedException {
        JsonNode manifest = json(get("/api/v1/sessions/" + closedWithErrors + "/replay", asA()));
        assertThat(manifest.path("status").asString()).isEqualTo("closed");
        assertThat(manifest.path("chunks")).hasSize(2);
        assertThat(manifest.path("chunks").get(0).path("seq").asInt()).isZero();
        assertThat(manifest.path("chunks").get(0).path("hasFullSnapshot").asBoolean()).isTrue();
        assertThat(manifest.path("chunks").get(1).path("hasFullSnapshot").asBoolean()).isFalse();
        assertThat(manifest.toString()).doesNotContain("tenants/");   // storage layout stays internal

        HttpResponse<String> chunk = get("/api/v1/sessions/" + closedWithErrors + "/replay/0", asA());
        assertThat(chunk.statusCode()).isEqualTo(200);
        assertThat(chunk.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(WireJson.mapper().readTree(chunk.body())).isEqualTo(WireJson.mapper().readTree(SNAPSHOT));

        HttpResponse<byte[]> gzipped = HTTP.send(request("/api/v1/sessions/" + closedWithErrors + "/replay/0", asA())
                .header("Accept-Encoding", "gzip").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(gzipped.headers().firstValue("Content-Encoding")).hasValue("gzip");
        try (var in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped.body()))) {
            assertThat(WireJson.mapper().readTree(in.readAllBytes())).isEqualTo(WireJson.mapper().readTree(SNAPSHOT));
        }

        assertThat(get("/api/v1/sessions/" + closedWithErrors + "/replay/7", asA()).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sessions/" + closedWithErrors + "/replay/-1", asA()).statusCode()).isEqualTo(404);
    }

    @Test
    void liveSessionsWorkLikeClosedOnes() {
        JsonNode manifest = json(get("/api/v1/sessions/" + openSession + "/replay", asA()));
        assertThat(manifest.path("status").asString()).isEqualTo("open");
        assertThat(manifest.path("chunks").get(0).path("hasFullSnapshot").asBoolean()).isTrue();
        assertThat(json(get("/api/v1/sessions/" + openSession + "/events", asA())).path("items")).hasSize(1);
        assertThat(get("/api/v1/sessions/" + openSession + "/replay/0", asA()).statusCode()).isEqualTo(200);
    }

    @Test
    void noPasswordsOrAuthorizationHeadersInLogs(CapturedOutput output) {
        get("/api/v1/sessions", basic(a.email(), "wrong-password-xyz"));
        get("/api/v1/sessions", asA());
        assertThat(output.getAll()).doesNotContain(DEV_PASSWORD).doesNotContain("wrong-password-xyz")
                .doesNotContain(Base64.getEncoder().encodeToString((a.email() + ":" + DEV_PASSWORD).getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ helpers

    private List<String> allSessionIds(String auth, String filters) {
        JsonNode page = json(get("/api/v1/sessions?limit=200" + filters, auth));
        return ids(page);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("items").forEach(i -> ids.add(i.path("id").asString()));
        return ids;
    }

    private String asA() {
        return basic(a.email(), DEV_PASSWORD);
    }

    private String asB() {
        return basic(b.email(), DEV_PASSWORD);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder request(String path, String auth) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        return builder;
    }

    private HttpResponse<String> get(String path, String auth) {
        try {
            return HTTP.send(request(path, auth).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return WireJson.mapper().readTree(response.body());
    }
}
