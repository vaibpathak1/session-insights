package io.sessioninsights.collector.tenant;

import com.github.benmanes.caffeine.cache.Ticker;
import io.sessioninsights.collector.CollectorIntegrationTest;
import io.sessioninsights.collector.CollectorTestInfra;
import io.sessioninsights.collector.CollectorTestInfra.SiteFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Cache behaviour against the real database. The cache clock is a fake ticker, so TTLs are
 * crossed deterministically; the spy only counts calls, the lookup itself is real.
 */
@Import(SiteKeyCacheTest.FakeTickerConfig.class)
class SiteKeyCacheTest extends CollectorIntegrationTest {

    static final AtomicLong NANOS = new AtomicLong();

    @TestConfiguration
    static class FakeTickerConfig {
        @Bean
        @Primary
        Ticker fakeTicker() {
            return NANOS::get;
        }
    }

    @MockitoSpyBean
    SiteKeyLookup lookup;

    @Test
    void repeatedRequestsWithOneKeyHitTheDatabaseOnce() {
        SiteFixture site = newSite();

        for (int i = 0; i < 5; i++) {
            assertThat(send(site.key()).statusCode()).isEqualTo(202);
        }

        verify(lookup, times(1)).find(site.keyHash());
    }

    @Test
    void unknownKeysAreNegativelyCachedForTheShortTtl() {
        String unknown = "sk_test_unknown_" + UUID.randomUUID();
        String hash = SiteKeyHash.of(unknown);

        assertThat(send(unknown).statusCode()).isEqualTo(401);
        assertThat(send(unknown).statusCode()).isEqualTo(401);
        verify(lookup, times(1)).find(hash);

        advance(Duration.ofSeconds(11));
        assertThat(send(unknown).statusCode()).isEqualTo(401);
        verify(lookup, times(2)).find(hash);
    }

    @Test
    void revokedKeyIsRefusedOncePositiveTtlPasses() {
        SiteFixture site = newSite();
        assertThat(send(site.key()).statusCode()).isEqualTo(202);

        CollectorTestInfra.revoke(site);
        assertThat(send(site.key()).statusCode()).as("still cached").isEqualTo(202);

        advance(Duration.ofSeconds(61));
        assertThat(send(site.key()).statusCode()).isEqualTo(401);
    }

    private java.net.http.HttpResponse<String> send(String key) {
        return post("/v1/events").header("X-SI-Key", key)
                .body(batchJson(UUID.randomUUID(), eventJson(UUID.randomUUID(), now())))
                .send();
    }

    private static void advance(Duration duration) {
        NANOS.addAndGet(duration.toNanos());
    }
}
