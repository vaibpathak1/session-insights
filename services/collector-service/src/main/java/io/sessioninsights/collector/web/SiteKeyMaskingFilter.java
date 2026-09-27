package io.sessioninsights.collector.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Masks the {@code k} (site key) query parameter in {@link HttpServletRequest#getQueryString()}
 * for everything downstream, so framework request logging (e.g. {@code DispatcherServlet}
 * at DEBUG) never prints a key. {@code getParameter("k")} is unaffected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SiteKeyMaskingFilter extends OncePerRequestFilter {

    private static final Pattern KEY_PARAM = Pattern.compile("(^|&)(" + CorsHeaders.KEY_PARAM + "=)[^&]*");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String query = request.getQueryString();
        if (query == null || !query.contains(CorsHeaders.KEY_PARAM + "=")) {
            chain.doFilter(request, response);
            return;
        }
        String masked = KEY_PARAM.matcher(query).replaceAll("$1$2***");
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getQueryString() {
                return masked;
            }
        }, response);
    }
}
