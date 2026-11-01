package com.acme.kb.p0.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对象键的日志替身：只输出不可逆前缀摘要。
 * 完整键含租户与 KB 的内部路径结构，落到日志就等于外泄存储布局；运维对账直接查库或列桶。
 */
public final class KeyRef {

    private KeyRef() {
    }

    public static String of(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return "obj:-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectKey.getBytes(StandardCharsets.UTF_8));
            return "obj:" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
