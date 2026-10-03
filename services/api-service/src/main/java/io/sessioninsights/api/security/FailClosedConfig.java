package io.sessioninsights.api.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Any profile other than {@code dev} refuses to start (ADR-0013): real authentication (OIDC)
 * arrives in Phase 11, and until then the API must never run unauthenticated or with the
 * shared dev password.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!dev")
class FailClosedConfig {

    @Bean
    InitializingBean refuseToStartWithoutAuthentication() {
        return () -> {
            throw new IllegalStateException("api-service has no authentication for this profile: only the 'dev' "
                    + "profile (HTTP Basic with DEV_ADMIN_PASSWORD) is available until OIDC (Phase 11). "
                    + "Refusing to start.");
        };
    }
}
