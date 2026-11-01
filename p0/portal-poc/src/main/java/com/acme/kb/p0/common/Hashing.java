package com.acme.kb.p0.common;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(byte[] data) {
        return hex(digest().digest(data));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256Hex(InputStream in) {
        MessageDigest d = digest();
        byte[] buf = new byte[1 << 16];
        try (InputStream stream = in) {
            int read;
            while ((read = stream.read(buf)) > 0) {
                d.update(buf, 0, read);
            }
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "无法完成校验计算");
        }
        return hex(d.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
