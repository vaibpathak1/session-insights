package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorTestInfra;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka unreachable: the collector must answer 503 quickly, never hang and never 202. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.producer.properties.max.block.ms=500")
class KafkaUnavailableTest {

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        CollectorTestInfra.registerDatabase(registry);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String bootstrap = "localhost:" + closedPort;
        registry.add("spring.kafka.bootstrap-servers", () -> bootstrap);
    }

    @Test
    void returns503WithRetryAfter() throws Exception {
        SiteFixture site = CollectorTestInfra.newSite("http://localhost:*");
        String body = """
                {"sessionId":"%s","events":[{"clientEventId":"%s","type":"CLICK","ts":%d}]}"""
                .formatted(UUID.randomUUID(), UUID.randomUUID(), System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/events"))
                .timeout(Duration.ofSeconds(15))
                .header("X-SI-Key", site.key())
                .header("Origin", "http://localhost:3000")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"unavailable\"}");
        assertThat(response.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(took).isLessThan(Duration.ofSeconds(5));
    }
}
