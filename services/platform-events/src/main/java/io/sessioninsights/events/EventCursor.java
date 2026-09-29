package io.sessioninsights.events;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Position after an event in a session's {@code (ts, event_id)} order, as an opaque URL-safe
 * token. Keyset pagination: stable while new events arrive.
 */
public record EventCursor(Instant ts, UUID eventId) {

    public static EventCursor after(EventRow row) {
        return new EventCursor(row.ts(), row.eventId());
    }

    public String encode() {
        String raw = ts.toEpochMilli() + ":" + eventId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException for anything that is not a token from {@link #encode} */
    public static EventCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            return new EventCursor(Instant.ofEpochMilli(Long.parseLong(raw.substring(0, colon))),
                    UUID.fromString(raw.substring(colon + 1)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }
}
