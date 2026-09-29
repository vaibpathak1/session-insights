package io.sessioninsights.processor.sessions;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UserAgentsTest {

    private static final UserAgents PARSER = new UserAgents();

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 | desktop | Chrome 140",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 Edg/140.0.0.0 | desktop | Edge 140",
            "Mozilla/5.0 (X11; Linux x86_64; rv:142.0) Gecko/20100101 Firefox/142.0 | desktop | Firefox 142",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1 | mobile | Safari 18",
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 | mobile | Chrome 140",
            "Mozilla/5.0 (iPad; CPU OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1 | tablet | Safari 18",
            "Mozilla/5.0 (Linux; Android 14; SM-X910) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36 | tablet | Chrome 140",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html) | null | Googlebot 2",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/140.0.0.0 Safari/537.36 | null | HeadlessChrome 140",
            "curl/8.7.1 | null | curl 8",
            "something odd | null | null",
    })
    void platformAndBrowser(String ua, String platform, String browser) {
        UserAgents.Parsed parsed = PARSER.parse(ua);
        assertThat(parsed.platform()).isEqualTo(platform);
        assertThat(parsed.browser()).isEqualTo(browser);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"null", "''", "'   '"})
    void missingUserAgentIsUnknown(String ua) {
        assertThat(PARSER.parse(ua)).isEqualTo(new UserAgents.Parsed(null, null));
    }
}
