package com.wellsetups.wellui.editor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, process-local bearer sessions. Only token digests are retained. */
final class EditorSessions {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> expires = new LinkedHashMap<>();
    private final Clock clock;
    private final long lifetime;

    EditorSessions(Clock clock, Duration lifetime) {
        this.clock = clock;
        this.lifetime = lifetime.toMillis();
    }

    synchronized String issue() {
        expires.values().removeIf(expiry -> expiry <= clock.millis());
        if (expires.size() >= 128) expires.remove(expires.keySet().iterator().next());
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        expires.put(digest(token), clock.millis() + lifetime);
        return token;
    }

    synchronized boolean accepts(String authorization) {
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) return false;
        String key = digest(authorization.substring(7));
        Long expiry = expires.get(key);
        if (expiry == null) return false;
        if (expiry <= clock.millis()) {
            expires.remove(key);
            return false;
        }
        return true;
    }

    synchronized void clear() { expires.clear(); }

    private static String digest(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
