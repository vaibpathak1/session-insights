package io.sessioninsights.collector.web;

import java.util.List;
import java.util.Locale;

/**
 * Matches a request {@code Origin} against a site's allow-list. An entry is either an exact
 * origin ({@code https://shop.example.com}) or an origin with a port wildcard
 * ({@code http://localhost:*}), which also matches the origin without a port. No other
 * wildcards: {@code *} alone is never accepted.
 */
public final class OriginMatcher {

    private OriginMatcher() {
    }

    public static boolean matches(List<String> allowedOrigins, String origin) {
        if (origin == null || origin.isBlank() || origin.equals("null")) {
            return false;
        }
        String candidate = origin.toLowerCase(Locale.ROOT);
        for (String entry : allowedOrigins) {
            if (entry != null && matchesEntry(stripTrailingSlash(entry.trim().toLowerCase(Locale.ROOT)), candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesEntry(String entry, String origin) {
        if (!entry.endsWith(":*")) {
            return entry.equals(origin);
        }
        String base = entry.substring(0, entry.length() - 2);   // scheme://host
        if (!base.contains("://") || base.endsWith("/")) {
            return false;
        }
        if (origin.equals(base)) {
            return true;
        }
        if (!origin.startsWith(base + ":")) {
            return false;
        }
        String port = origin.substring(base.length() + 1);
        return !port.isEmpty() && port.length() <= 5 && port.chars().allMatch(Character::isDigit);
    }

    private static String stripTrailingSlash(String entry) {
        return entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
    }
}
