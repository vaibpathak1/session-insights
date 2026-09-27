package io.sessioninsights.domain;

import io.sessioninsights.domain.tenant.SiteKeys;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteKeysTest {

    /** Same vector as collector-service {@code SiteKeyHashTest}: both hashes must stay identical. */
    @Test
    void hashIsSha256HexOfThePlaintext() {
        assertThat(SiteKeys.hash("sk_dev_known-test-vector"))
                .isEqualTo("e255359f1532c1972d969487e4eff2defb770d482d5e171ceab354c3f58659dd");
    }

    @Test
    void issuedKeyHashMatchesAndToStringHidesPlaintext() {
        SiteKeys.IssuedKey key = SiteKeys.issue("dev");
        assertThat(key.hash()).isEqualTo(SiteKeys.hash(key.plaintext()));
        assertThat(key.toString()).doesNotContain(key.plaintext());
    }
}
