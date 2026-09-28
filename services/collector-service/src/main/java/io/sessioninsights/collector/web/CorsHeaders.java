package io.sessioninsights.collector.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * CORS response headers, set by hand (ADR-0012): preflights and 401/403 refusals echo any
 * real request origin; everything else only once the origin matched the site's allow-list.
 * Never {@code *}, never credentials.
 */
final class CorsHeaders {

    static final String KEY_HEADER = "X-SI-Key";
    static final String KEY_PARAM = "k";

    private CorsHeaders() {
    }

    /** On every response: caches must key on the request origin. */
    static void vary(HttpServletResponse response) {
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
    }

    /** A real origin to echo: present and not the opaque {@code null} origin. */
    static boolean echoable(String origin) {
        return origin != null && !origin.isBlank() && !origin.equalsIgnoreCase("null");
    }

    static void allow(HttpServletResponse response, String origin) {
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.RETRY_AFTER);
    }

    static void preflight(HttpServletResponse response) {
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                String.join(", ", HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_ENCODING, KEY_HEADER));
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "600");
    }
}
