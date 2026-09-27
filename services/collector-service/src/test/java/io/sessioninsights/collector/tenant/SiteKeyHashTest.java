package io.sessioninsights.collector.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteKeyHashTest {

    /** Same vector as platform-domain {@code SiteKeysTest}: both hashes must stay identical. */
    @Test
    void matchesSiteKeysHash() {
        assertThat(SiteKeyHash.of("sk_dev_known-test-vector"))
                .isEqualTo("e255359f1532c1972d969487e4eff2defb770d482d5e171ceab354c3f58659dd");
    }
}
