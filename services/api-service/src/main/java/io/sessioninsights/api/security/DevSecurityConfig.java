package io.sessioninsights.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * {@code dev} profile only (ADR-0013): stateless HTTP Basic against {@code DEV_ADMIN_PASSWORD},
 * which must be set (at least 12 characters) or the application does not start.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
class DevSecurityConfig {

    static final int MIN_PASSWORD_LENGTH = 12;

    @Bean
    DevAuthenticationProvider devAuthenticationProvider(@Value("${api.dev-auth.password:}") String password,
                                                        AppUserResolver users) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("DEV_ADMIN_PASSWORD must be set (at least " + MIN_PASSWORD_LENGTH
                    + " characters) to run api-service with the dev profile");
        }
        return new DevAuthenticationProvider(password, users);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, DevAuthenticationProvider provider) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())   // stateless, no cookies: nothing to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(provider)
                .httpBasic(withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .addFilterAfter(new TenantBindingFilter(), BasicAuthenticationFilter.class)
                .build();
    }
}
