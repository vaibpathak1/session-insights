package io.sessioninsights.api.sessions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in the session list's {@code (started_at desc, id desc)} order, as an opaque
 * URL-safe token. Stable while new sessions arrive: a page never repeats or skips a session.
 */
record SessionCursor(Instant startedAt, UUID id) {

    String encode() {
        long micros = Math.addExact(Math.multiplyExact(startedAt.getEpochSecond(), 1_000_000L), startedAt.getNano() / 1_000);
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static SessionCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, colon));
            return new SessionCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), UUID.fromString(raw.substring(colon + 1)));
        } catch (RuntimeException e) {
            throw new InvalidParameterException("cursor");
        }
    }
}
