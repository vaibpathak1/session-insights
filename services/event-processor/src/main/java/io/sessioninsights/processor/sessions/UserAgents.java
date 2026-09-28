package io.sessioninsights.processor.sessions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ua_parser.Client;
import ua_parser.Parser;

import java.util.Locale;
import java.util.Set;

/**
 * Session {@code platform} and {@code browser} from the raw User-Agent (task 5.2), with
 * uap-java (Apache-2.0). Yauaa was measured first and rejected: ~1.5 s to initialise and
 * ~130 MB of heap, over the 100 MB budget; uap-java takes ~0.1 s and ~1 MB.
 * <p>
 * uap-java has no device class, so {@code platform} is derived: bots and headless browsers →
 * null; iPad, or Android without "Mobile" → tablet; iPhone/iPod or a "Mobile" UA → mobile;
 * a desktop OS → desktop; anything else → null. Results are cached per distinct UA string.
 */
public final class UserAgents {

    public static final String DESKTOP = "desktop";
    public static final String MOBILE = "mobile";
    public static final String TABLET = "tablet";

    /** Platform and browser ("Chrome 140"); either may be null when unknown. */
    public record Parsed(String platform, String browser) {
        static final Parsed UNKNOWN = new Parsed(null, null);
    }

    private static final Set<String> DESKTOP_OS = Set.of(
            "Mac OS X", "Windows", "Linux", "Ubuntu", "Fedora", "Debian", "Chrome OS", "ChromeOS", "FreeBSD");

    private final Parser parser = new Parser();
    private final Cache<String, Parsed> cache = Caffeine.newBuilder().maximumSize(10_000).build();

    public Parsed parse(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return Parsed.UNKNOWN;
        }
        return cache.get(userAgent, this::analyse);
    }

    private Parsed analyse(String ua) {
        Client client;
        try {
            client = parser.parse(ua);
        } catch (RuntimeException e) {
            return Parsed.UNKNOWN;
        }
        return new Parsed(platform(ua, client), browser(client));
    }

    private static String platform(String ua, Client client) {
        String device = client.device == null ? "" : client.device.family;
        String os = client.os == null ? "" : client.os.family;
        String agent = client.userAgent == null ? "" : client.userAgent.family;
        if ("Spider".equals(device) || agent.toLowerCase(Locale.ROOT).contains("headless")) {
            return null;
        }
        boolean mobileToken = ua.contains("Mobile");
        if ("iPad".equals(device) || ua.contains("Tablet") || ("Android".equals(os) && !mobileToken)) {
            return TABLET;
        }
        if ("iPhone".equals(device) || "iPod".equals(device) || mobileToken || "iOS".equals(os)) {
            return MOBILE;
        }
        return DESKTOP_OS.contains(os) || os.startsWith("Windows") ? DESKTOP : null;
    }

    private static String browser(Client client) {
        if (client.userAgent == null || client.userAgent.family == null || "Other".equals(client.userAgent.family)) {
            return null;
        }
        String family = client.userAgent.family
                .replaceFirst("^Mobile ", "")
                .replaceFirst(" Mobile( iOS| WebView)?$", "")
                .replaceFirst(" iOS$", "");
        String major = client.userAgent.major;
        return major == null || major.isBlank() ? family : family + " " + major;
    }
}
