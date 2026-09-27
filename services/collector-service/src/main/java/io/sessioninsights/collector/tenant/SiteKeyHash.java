package io.sessioninsights.collector.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex of a site key, identical to {@code SiteKeys.hash} in platform-domain (the
 * collector must not depend on the JPA module). {@code SiteKeyHashTest} pins both to one vector.
 */
public final class SiteKeyHash {

    private SiteKeyHash() {
    }

    public static String of(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
