package io.sessioninsights.api.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Maps a login to its user and tenant with {@code resolve_app_user()} (V6), the API's single
 * pre-tenant read (ADR-0013). An email that exists in several tenants is refused, never guessed.
 */
@Component
public class AppUserResolver {

    private final JdbcClient jdbc;

    public AppUserResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ApiPrincipal> resolve(String email) {
        List<ApiPrincipal> users = jdbc.sql("SELECT user_id, tenant_id, role FROM resolve_app_user(:email)")
                .param("email", email)
                .query((rs, n) -> new ApiPrincipal(rs.getObject("user_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), email, rs.getString("role")))
                .list();
        return users.size() == 1 ? Optional.of(users.getFirst()) : Optional.empty();
    }
}
