package io.sessioninsights.collector.web;

import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "collector.rate-limit.capacity=2",
        "collector.rate-limit.refill-tokens=1",
        "collector.rate-limit.refill-period=1h"})
class RateLimitTest extends CollectorIntegrationTest {

    @Test
    void exceedingTheBucketIs429WithRetryAfter() {
        SiteFixture site = newSite();
        SiteFixture other = newSite();

        assertThat(send(site).statusCode()).isEqualTo(202);
        assertThat(send(site).statusCode()).isEqualTo(202);
        HttpResponse<String> limited = send(site);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).isEqualTo("{\"error\":\"rate_limited\"}");
        assertThat(limited.headers().firstValue("Retry-After")).get().satisfies(v -> assertThat(Long.parseLong(v)).isBetween(3500L, 3600L));
        assertThat(limited.headers().firstValue("Access-Control-Allow-Origin")).hasValue(ORIGIN);
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers")).hasValue("Retry-After");
        assertThat(send(other).statusCode()).as("limits are per key").isEqualTo(202);
    }

    private HttpResponse<String> send(SiteFixture site) {
        return post("/v1/events").key(site).body(batchJson(UUID.randomUUID(), eventJson(UUID.randomUUID(), now()))).send();
    }
}
