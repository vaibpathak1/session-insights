package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With debug logging on for the collector and Spring MVC, neither a site key nor any body
 * content may appear in the output, on success or on any error path.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "logging.level.io.sessioninsights=DEBUG",
        "logging.level.org.springframework.web=DEBUG"})
class NoSensitiveLoggingTest extends CollectorIntegrationTest {

    @Test
    void neitherKeysNorPayloadsAreLogged(CapturedOutput output) {
        SiteFixture site = newSite();
        String marker = "private-marker-" + UUID.randomUUID();
        String unknownKey = "sk_test_unknown_" + UUID.randomUUID();
        UUID session = UUID.randomUUID();
        String valid = batchJson(session, """
                {"clientEventId":"%s","type":"CLICK","ts":%d,"targetText":"%s"}""".formatted(UUID.randomUUID(), now(), marker));

        assertThat(post("/v1/events").key(site).body(valid).send().statusCode()).isEqualTo(202);
        assertThat(post("/v1/events?k=" + site.key()).header("Content-Type", "text/plain").body(valid).send().statusCode()).isEqualTo(202);
        assertThat(post("/v1/events").header("X-SI-Key", unknownKey).body(valid).send().statusCode()).isEqualTo(401);
        assertThat(post("/v1/events?k=" + unknownKey).body(valid).send().statusCode()).isEqualTo(401);
        assertThat(post("/v1/events").key(site).body("{\"sessionId\":\"" + marker + "\"}").send().statusCode()).isEqualTo(400);
        assertThat(post("/v1/events").key(site).body("{" + marker).send().statusCode()).isEqualTo(400);
        assertThat(post("/v1/events").key(site).body(valid.replace("CLICK", marker)).send().statusCode()).isEqualTo(400);
        assertThat(post("/v1/events").key(site).header("Origin", "https://" + marker + ".example").body(valid).send().statusCode()).isEqualTo(403);
        assertThat(options("/v1/events?k=" + site.key()).send().statusCode()).isEqualTo(204);

        assertThat(output.getAll())
                .as("sanity: debug logging is on and captured")
                .contains("/v1/events")
                .contains("k=***")
                .doesNotContain(site.key())
                .doesNotContain(unknownKey)
                .doesNotContain(marker);
    }
}
