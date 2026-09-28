package io.sessioninsights.processor.sessions;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page counting over the ordered, fragment-less URLs of the counted navigations (ClickHouse
 * already drops replaceState and cuts fragments); verified against ClickHouse in
 * {@link SessionCloserTest}.
 */
class SessionStatsTest {

    @Test
    void countsChangesOfTheUrlWithoutFragment() {
        // load a, hash change on a, push b, popstate to b#top, back to a, then c
        assertThat(SessionStats.pageCount(List.of("http://x/a", "http://x/a", "http://x/b", "http://x/b",
                "http://x/a", "http://x/c"))).isEqualTo(4);
        assertThat(SessionStats.pageCount(List.of("http://x/a"))).isEqualTo(1);
        assertThat(SessionStats.pageCount(List.of())).isZero();
    }
}
