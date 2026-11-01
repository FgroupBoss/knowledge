package com.acme.kb.p1.config;

import com.acme.kb.p1.common.Secrets;
import com.acme.kb.p1.storage.ObjectStorageService;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储装配。凭据缺失时直接启动失败：空口令的 MinIO 客户端会在第一次写入时才报错，
 * 那时已经分不清是配置问题还是网络问题。异常信息只说明"缺配置"，绝不带上值本身。
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public MinioClient minioClient(KbProperties props) {
        KbProperties.Storage storage = props.storage();
        String accessKey = Secrets.resolveOrNull(storage.accessKey(), storage.accessKeyFile());
        String secretKey = Secrets.resolveOrNull(storage.secretKey(), storage.secretKeyFile());
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException("缺少对象存储凭据：请设置 KB_MINIO_ACCESS_KEY/KB_MINIO_SECRET_KEY "
                    + "或对应的 *_FILE（见 contracts/config-catalog.md）");
        }
        log.info("minio endpoint={} bucket={}", storage.endpoint(), storage.bucket());
        return MinioClient.builder()
                .endpoint(storage.endpoint())
                .credentials(accessKey, secretKey)
                .build();
    }

    /** bean 名即指示器名：MinIO 是原件唯一权威，readiness 必须包含它，否则"就绪"是假的。 */
    @Bean
    public HealthIndicator storageProbe(ObjectStorageService storage) {
        return () -> {
            try {
                storage.assertBucketReady();
                return Health.up().withDetail("bucket", storage.bucket()).build();
            } catch (RuntimeException e) {
                return Health.down().withDetail("reason", "bucket unavailable").build();
            }
        };
    }
}
