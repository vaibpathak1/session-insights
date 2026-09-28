package io.sessioninsights.collector.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers CORS preflights for {@code /v1/*} before Spring MVC's generic CORS handling
 * (ADR-0012). Every preflight succeeds: it echoes the request {@code Origin} and allows
 * {@code POST} with the SDK's headers, cacheable for 600 s. Authorization (site key and the
 * site's origin allow-list) happens on the actual request, whose refusals the browser can
 * then read. No key lookup here, so preflights never touch the database. A missing or
 * {@code null} origin is never echoed; without the header the browser blocks the request.
 */
@Component
public class CorsPreflightFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.OPTIONS.matches(request.getMethod()) || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        CorsHeaders.vary(response);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (CorsHeaders.echoable(origin)) {
            CorsHeaders.allow(response, origin);
            CorsHeaders.preflight(response);
        }
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
