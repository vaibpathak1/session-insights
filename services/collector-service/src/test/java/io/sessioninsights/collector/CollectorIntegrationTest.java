package io.sessioninsights.collector;

import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The real collector on a random port against real PostgreSQL and Kafka. Requests go
 * through {@link HttpClient} so headers ({@code Origin}, {@code Content-Encoding}) are sent
 * exactly as a browser would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class CollectorIntegrationTest {

    protected static final String ORIGIN = "http://localhost:3000";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CollectorTestInfra.registerDatabase(registry);
        CollectorTestInfra.registerKafka(registry);
    }

    /** A site allowing {@code http://localhost:*}, the dev seed's origin pattern. */
    protected static SiteFixture newSite() {
        return CollectorTestInfra.newSite("http://localhost:*", "https://shop.example.com");
    }

    protected Request post(String path) {
        return new Request("POST", path);
    }

    protected Request options(String path) {
        return new Request("OPTIONS", path);
    }

    protected static String eventJson(UUID clientEventId, long ts) {
        return """
                {"clientEventId":"%s","type":"CLICK","ts":%d,"url":"http://localhost:3000/cart","path":"/cart",
                 "targetSelector":"button#buy","targetText":"Buy"}""".formatted(clientEventId, ts);
    }

    protected static String batchJson(UUID sessionId, String... events) {
        return """
                {"sessionId":"%s","anonymousId":"anon-1","sdkVersion":"0.1.0","events":[%s]}"""
                .formatted(sessionId, String.join(",", events));
    }

    protected static long now() {
        return Instant.now().toEpochMilli();
    }

    /** Small request builder; defaults to a JSON body from {@link #ORIGIN}. */
    protected final class Request {
        private final String method;
        private final String path;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        private Request(String method, String path) {
            this.method = method;
            this.path = path;
            headers.put("Origin", ORIGIN);
            headers.put("Content-Type", "application/json");
        }

        public Request header(String name, String value) {
            if (value == null) {
                headers.remove(name);
            } else {
                headers.put(name, value);
            }
            return this;
        }

        public Request key(SiteFixture site) {
            return header("X-SI-Key", site.key());
        }

        public Request body(String json) {
            return body(json.getBytes(StandardCharsets.UTF_8));
        }

        public Request body(byte[] bytes) {
            this.body = bytes;
            return this;
        }

        public HttpResponse<String> send() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            try {
                return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
