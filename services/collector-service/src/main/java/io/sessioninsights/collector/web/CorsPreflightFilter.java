package io.sessioninsights.collector.web;

import io.sessioninsights.collector.config.CollectorProperties;
import io.sessioninsights.collector.tenant.ResolvedSite;
import io.sessioninsights.collector.tenant.SiteKeyHash;
import io.sessioninsights.collector.tenant.SiteKeyResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.Optional;

/**
 * Answers CORS preflights for {@code /v1/*} before Spring MVC's generic CORS handling.
 * A preflight cannot carry {@code X-SI-Key}, so the site is identified by {@code ?k=}; the
 * origin is echoed only when it is on that site's allow-list. Anything else gets a bare
 * {@code 403} and the browser blocks the request. Browser clients must always send the key
 * as {@code ?k=} (ADR-0010); with a {@code text/plain} body and no custom headers they
 * never trigger a preflight at all. A successful preflight is cacheable for 600 s.
 */
@Component
public class CorsPreflightFilter extends OncePerRequestFilter {

    private final SiteKeyResolver resolver;
    private final int maxSiteKeyLength;

    public CorsPreflightFilter(SiteKeyResolver resolver, CollectorProperties properties) {
        this.resolver = resolver;
        this.maxSiteKeyLength = properties.limits().maxSiteKeyLength();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.OPTIONS.matches(request.getMethod()) || !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        CorsHeaders.vary(response);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String key = request.getParameter(CorsHeaders.KEY_PARAM);
        if (origin == null || key == null || key.isBlank() || key.length() > maxSiteKeyLength) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Optional<ResolvedSite> site;
        try {
            site = resolver.resolve(SiteKeyHash.of(key.trim()));
        } catch (DataAccessException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (site.isEmpty() || !OriginMatcher.matches(site.get().allowedOrigins(), origin)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        CorsHeaders.allow(response, origin);
        CorsHeaders.preflight(response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
