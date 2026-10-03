package io.sessioninsights.api.security;

import io.sessioninsights.domain.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs the rest of an authenticated request as the user's tenant (ADR-0013): every PostgreSQL
 * transaction then sets {@code app.tenant_id} (row-level security), and controllers pass the
 * same tenant to ClickHouse and object-storage reads. No principal, no tenant: RLS returns nothing.
 */
public class TenantBindingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof ApiPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            TenantContext.callAs(principal.tenantId(), () -> {
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
            });
        } catch (FilterFailure f) {
            if (f.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) f.getCause();
        }
    }

    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
