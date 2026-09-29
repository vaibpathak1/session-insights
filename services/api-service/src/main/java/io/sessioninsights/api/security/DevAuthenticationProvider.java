package io.sessioninsights.api.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Dev-grade HTTP Basic (ADR-0013, replaced by OIDC in Phase 11): the password is the single
 * {@code DEV_ADMIN_PASSWORD}, compared in constant time over SHA-256 digests (no bcrypt on
 * every request, no length leak); the username is an active app user's email, resolved to its
 * tenant. Failures say nothing about which part was wrong.
 */
public class DevAuthenticationProvider implements AuthenticationProvider {

    private final byte[] passwordDigest;
    private final AppUserResolver users;

    public DevAuthenticationProvider(String password, AppUserResolver users) {
        this.passwordDigest = sha256(password);
        this.users = users;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = authentication.getName();
        Object credentials = authentication.getCredentials();
        boolean passwordOk = credentials != null
                && MessageDigest.isEqual(passwordDigest, sha256(credentials.toString()));
        if (!passwordOk || email == null || email.isBlank()) {
            throw new BadCredentialsException("bad credentials");
        }
        ApiPrincipal principal = users.resolve(email.trim())
                .orElseThrow(() -> new BadCredentialsException("bad credentials"));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
