package io.sessioninsights.common.privacy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "contact jane.doe+x@example.co.uk now | contact [email] now",
            "card 4111 1111 1111 1111 declined     | card [card] declined",
            "card 4111-1111-1111-1111.             | card [card].",
            "pan=5500005555555559                  | pan=[card]",
            "call +1 (555) 123-4567 today          | call [phone] today",
            "call +44 20 7946 0958                 | call [phone]",
            "call (555) 123-4567                   | call [phone]",
            "call 555-123-4567 or 555.123.4567     | call [phone] or [phone]",
    })
    void masksPii(String input, String expected) {
        assertThat(Redactor.redact(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "order 12345 failed",
            "at 2026-09-27 13:18:32.316",
            "id 0192f5a4-0000-7000-8000-000000000001",
            "id 12345678-1234-1234-1234-123456789012",
            "TypeError: x is undefined at app.js:120:15",
            "version 1.2.3",
    })
    void leavesOrdinaryTextAlone(String input) {
        assertThat(Redactor.redact(input)).isEqualTo(input);
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(Redactor.redact(null)).isNull();
        assertThat(Redactor.redactUrl(null)).isNull();
        assertThat(Redactor.redact("")).isEmpty();
    }

    @Test
    void urlRedactsQueryValuesAndFragmentButKeepsPath() {
        assertThat(Redactor.redactUrl("https://shop.test/checkout/step-2?email=jane%40example.com&plan=pro#card=4111111111111111"))
                .isEqualTo("https://shop.test/checkout/step-2?email=%5Bemail%5D&plan=pro#card%3D%5Bcard%5D");
        assertThat(Redactor.redactUrl("https://shop.test/orders/1234567890123?page=2"))
                .isEqualTo("https://shop.test/orders/1234567890123?page=2");
        assertThat(Redactor.redactUrl("https://shop.test/a?bad=%zz&q=jane@example.com"))
                .isEqualTo("https://shop.test/a?bad=%zz&q=%5Bemail%5D");
    }
}
