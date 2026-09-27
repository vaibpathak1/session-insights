package io.sessioninsights.domain.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Issues site keys. The plaintext is returned once to the caller and never stored. */
public final class SiteKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PREFIX_LENGTH = 12;

    private SiteKeys() {
    }

    public record IssuedKey(String plaintext, String prefix, String hash) {
        @Override
        public String toString() {
            return "IssuedKey[prefix=" + prefix + "]";   // keep the plaintext out of logs by accident
        }
    }

    public static IssuedKey issue(String environment) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String plaintext = "sk_" + environment + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new IssuedKey(plaintext, plaintext.substring(0, PREFIX_LENGTH), hash(plaintext));
    }

    public static String hash(String plaintext) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
