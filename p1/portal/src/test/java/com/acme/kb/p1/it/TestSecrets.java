package com.acme.kb.p1.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 集成测试凭据解析：环境变量优先，其次读本机 secrets 文件，两者都缺即失败。
 * 代码里不保留任何默认口令（AGENTS.md 红线 1）。
 */
final class TestSecrets {

    private static final Map<String, String> P1_FILES = Map.of(
            "KB_TEST_DDL_PASSWORD", "p1_db_ddl_password.txt",
            "KB_TEST_APP_PASSWORD", "p1_db_password.txt");

    private static final Map<String, String> P0_FILES = Map.of(
            "KB_TEST_REDIS_PASSWORD", "redis_password.txt",
            "KB_MINIO_ACCESS_KEY", "minio_access_key.txt",
            "KB_MINIO_SECRET_KEY", "minio_secret_key.txt");

    private TestSecrets() {
    }

    static String secret(String envVar) {
        String fromEnv = System.getenv(envVar);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        Path file = P1_FILES.containsKey(envVar)
                ? p1Secrets().resolve(P1_FILES.get(envVar))
                : p0Secrets().resolve(P0_FILES.get(envVar));
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("缺少测试凭据：设置环境变量 " + envVar
                    + "，或提供文件 " + file.toAbsolutePath() + "（见 p1/compose/README.md 第 4 节）");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("读取凭据文件失败：" + file, e);
        }
    }

    private static Path p1Secrets() {
        return override("KB_P1_SECRETS", "../compose/secrets");
    }

    private static Path p0Secrets() {
        return override("KB_P0_SECRETS", "../../p0/compose/secrets");
    }

    private static Path override(String dirEnv, String relativeToPortal) {
        String dir = System.getenv(dirEnv);
        return dir == null || dir.isBlank()
                ? Paths.get(relativeToPortal).normalize()
                : Paths.get(dir).normalize();
    }
}
