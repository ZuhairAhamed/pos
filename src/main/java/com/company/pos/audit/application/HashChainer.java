package com.company.pos.audit.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Pure SHA-256 chain hashing for the audit trail. Each record's hash binds its own content to
 *  the previous record's hash, so any edit/deletion/reorder breaks the chain detectably. */
final class HashChainer {

    private HashChainer() {
    }

    static String chainHash(long seq, Instant occurredAt, String actor, String action,
            String entityRef, String payload, String prevHash) {
        String content = String.join("",
                Long.toString(seq),
                occurredAt.toString(),
                nullToEmpty(actor),
                nullToEmpty(action),
                nullToEmpty(entityRef),
                nullToEmpty(payload),
                nullToEmpty(prevHash));
        return sha256Hex(content);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
