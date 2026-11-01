package com.acme.kb.p1.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读取 Docker secrets 文件。Windows/Git Bash 生成的文件可能带 CRLF，
 * 而 MinIO 等按字节算签名，多一个 \r 就表现为签名不匹配，因此统一裁剪。
 */
public final class Secrets {

    private Secrets() {
    }

    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 secret: " + path.getFileName(), e);
        }
    }

    public static String readOrNull(String maybePath) {
        if (maybePath == null || maybePath.isBlank()) {
            return null;
        }
        Path p = Path.of(maybePath);
        return Files.isReadable(p) ? read(p) : null;
    }

    /** 优先内联值，其次 *_FILE 指向的文件。 */
    public static String resolve(String inlineValue, String filePath) {
        String value = resolveOrNull(inlineValue, filePath);
        if (value == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "缺少凭据配置");
        }
        return value;
    }

    /** 同上，但缺失时返回 null：由调用方决定 fail-close 的具体错误。 */
    public static String resolveOrNull(String inlineValue, String filePath) {
        if (inlineValue != null && !inlineValue.isBlank()) {
            return inlineValue;
        }
        return readOrNull(filePath);
    }
}
