package io.sessioninsights.collector.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OriginMatcherTest {

    static final List<String> ALLOWED = List.of("http://localhost:*", "https://shop.example.com/", "*");

    @ParameterizedTest
    @CsvSource({
            "http://localhost:3000,          true",
            "http://localhost:65535,         true",
            "http://localhost,               true",
            "HTTP://LOCALHOST:8080,          true",
            "https://shop.example.com,       true",
            "https://localhost:3000,         false",
            "http://localhost:,              false",
            "http://localhost:123456,        false",
            "http://localhost:3000/path,     false",
            "http://localhost.evil.com:3000, false",
            "http://localhost:3000.evil.com, false",
            "https://shop.example.com:8443,  false",
            "https://evil.shop.example.com,  false",
            "https://anything.example,       false",
            "null,                           false",
    })
    void matches(String origin, boolean expected) {
        assertThat(OriginMatcher.matches(ALLOWED, origin)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void missingOriginNeverMatches() {
        assertThat(OriginMatcher.matches(ALLOWED, null)).isFalse();
        assertThat(OriginMatcher.matches(ALLOWED, " ")).isFalse();
        assertThat(OriginMatcher.matches(List.of(), "http://localhost:3000")).isFalse();
    }
}
