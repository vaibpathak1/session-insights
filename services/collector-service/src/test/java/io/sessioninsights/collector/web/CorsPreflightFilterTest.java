package io.sessioninsights.collector.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0012: preflights are answered without a key lookup. The filter has no dependencies at
 * all, so an unknown key or a disallowed origin cannot reach the database or change the answer.
 */
class CorsPreflightFilterTest {

    private final CorsPreflightFilter filter = new CorsPreflightFilter();

    @Test
    void everyV1PreflightIs204EchoingTheOrigin() throws Exception {
        for (String uri : new String[] {"/v1/events?k=sk_unknown", "/v1/replay", "/v1/events?k="}) {
            MockHttpServletResponse response = run("OPTIONS", uri, "https://any.example");
            assertThat(response.getStatus()).isEqualTo(204);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://any.example");
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("POST");
            assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("600");
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
            assertThat(response.getHeader("Vary")).isEqualTo("Origin");
        }
    }

    @Test
    void neverEchoesAMissingOrNullOrigin() throws Exception {
        for (String origin : new String[] {null, "null", " "}) {
            MockHttpServletResponse response = run("OPTIONS", "/v1/events", origin);
            assertThat(response.getStatus()).isEqualTo(204);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
            assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
        }
    }

    @Test
    void postsAndOtherPathsPassThrough() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/v1/events");
        filter.doFilter(post, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(post);

        MockFilterChain other = new MockFilterChain();
        MockHttpServletRequest actuator = new MockHttpServletRequest("OPTIONS", "/actuator/health");
        filter.doFilter(actuator, new MockHttpServletResponse(), other);
        assertThat(other.getRequest()).isSameAs(actuator);
    }

    private MockHttpServletResponse run(String method, String uri, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri.replaceAll("\\?.*", ""));
        request.setRequestURI(uri.replaceAll("\\?.*", ""));
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("preflight is answered, not forwarded").isNull();
        return response;
    }
}
