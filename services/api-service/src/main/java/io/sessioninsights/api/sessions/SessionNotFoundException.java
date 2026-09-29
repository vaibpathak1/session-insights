package io.sessioninsights.api.sessions;

/**
 * No such session for the caller's tenant; answered with {@code 404}. Another tenant's session
 * is indistinguishable from a missing one (ADR-0013): RLS hides it, so it is simply not found.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException() {
        super("session not found", null, false, false);
    }
}
