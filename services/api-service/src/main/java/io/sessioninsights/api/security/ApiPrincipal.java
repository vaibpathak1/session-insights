package io.sessioninsights.api.security;

import java.security.Principal;
import java.util.UUID;

/** The authenticated API user and the tenant every read is bound to (ADR-0013). */
public record ApiPrincipal(UUID userId, UUID tenantId, String email, String role) implements Principal {

    @Override
    public String getName() {
        return email;
    }
}
