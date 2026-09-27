package io.sessioninsights.common.privacy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Server-side redaction of free text (ADR-0006, defence in depth: the SDK masks first).
 * Emails, card-like digit runs and phone numbers are replaced by fixed tokens. Patterns are
 * deliberately conservative: bare digit runs shorter than 13 are left alone so ids, dates
 * and timestamps stay readable.
 */
public final class Redactor {

    public static final String EMAIL = "[email]";
    public static final String CARD = "[card]";
    public static final String PHONE = "[phone]";

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    // 13-19 digits, optionally grouped by single spaces or dashes; not part of a longer token
    private static final Pattern CARD_PATTERN =
            Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])");
    // +<country> international (8-15 digits), (555) 123-4567, or 555-123-4567 / 555.123.4567
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?<![\\w+])\\+\\d(?:[ ().-]{0,2}\\d){7,14}(?!\\w)"
                    + "|(?<![\\w-])(?:\\(\\d{3}\\) ?|\\d{3}[.-])\\d{3}[.-]\\d{4}(?![\\w-])");

    private Redactor() {
    }

    /** Redacts free text; {@code null} stays {@code null}. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        out = CARD_PATTERN.matcher(out).replaceAll(CARD);
        return PHONE_PATTERN.matcher(out).replaceAll(PHONE);
    }

    /**
     * Redacts query-string values and the fragment of a URL; scheme, host and path are kept
     * so pages still group. Values are URL-decoded before matching ({@code a%40b.com}).
     */
    public static String redactUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int hash = url.indexOf('#');
        String fragment = hash >= 0 ? url.substring(hash + 1) : null;
        String beforeFragment = hash >= 0 ? url.substring(0, hash) : url;
        int question = beforeFragment.indexOf('?');

        StringBuilder out = new StringBuilder(url.length());
        if (question < 0) {
            out.append(beforeFragment);
        } else {
            out.append(beforeFragment, 0, question + 1).append(redactQuery(beforeFragment.substring(question + 1)));
        }
        if (fragment != null) {
            out.append('#').append(redactEncoded(fragment));
        }
        return out.toString();
    }

    private static String redactQuery(String query) {
        StringBuilder out = new StringBuilder(query.length());
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.append(redactEncoded(pair));
            } else {
                out.append(pair, 0, eq + 1).append(redactEncoded(pair.substring(eq + 1)));
            }
        }
        return out.toString();
    }

    /** Decodes, redacts, and re-encodes only if something was masked; otherwise unchanged. */
    private static String redactEncoded(String value) {
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            decoded = value;
        }
        String redacted = redact(decoded);
        return redacted.equals(decoded) ? value : URLEncoder.encode(redacted, StandardCharsets.UTF_8);
    }
}
