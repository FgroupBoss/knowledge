package com.acme.kb.p0.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 *_FILE 环境变量（Docker secrets）解析成 Spring 属性，使容器内运行只以文件方式拿凭据；
 * 宿主运行时可直接用内联值。文件内容一律裁剪首尾空白（含 \r），避免签名/口令错位。
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Map<String, String> ENV_TO_PROPERTY = Map.of(
            "P0_DB_USER_FILE", "spring.datasource.username",
            "P0_DB_PASSWORD_FILE", "spring.datasource.password",
            "P0_REDIS_PASSWORD_FILE", "spring.data.redis.password",
            "P0_MINIO_ACCESS_KEY_FILE", "p0.storage.access-key",
            "P0_MINIO_SECRET_KEY_FILE", "p0.storage.secret-key",
            "P0_ENGINE_TOKEN_FILE", "p0.engine.token",
            "P0_FIXTURE_TOKEN_FILE", "p0.fixture.token"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        ENV_TO_PROPERTY.forEach((envName, property) -> {
            String path = System.getProperty(envName, System.getenv(envName));
            if (path == null || path.isBlank()) {
                return;
            }
            try {
                String value = Files.readString(Path.of(path), StandardCharsets.UTF_8).strip();
                resolved.put(property, value);
            } catch (IOException e) {
                throw new IllegalStateException("secret file unreadable for " + envName, e);
            }
        });
        if (!resolved.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource("p0Secrets", resolved));
        }
    }
}
