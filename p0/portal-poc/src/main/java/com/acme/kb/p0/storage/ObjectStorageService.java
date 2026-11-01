package com.acme.kb.p0.storage;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.config.P0Properties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/** MinIO 是原始文件的唯一权威副本；桶私有、对象键由服务端生成、不签发长期 URL。 */
@Service
public class ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final MinioClient client;
    private final P0Properties.Storage config;

    public ObjectStorageService(MinioClient client, P0Properties config) {
        this.client = client;
        this.config = config.storage();
    }

    public String bucket() {
        return config.bucket();
    }

    public void putFile(String objectKey, Path file, long size, String contentType) {
        try (InputStream in = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(config.bucket())
                    .object(objectKey)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("storage_put_failed key={} err={}", KeyRef.of(objectKey), e.toString());
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储写入失败");
        }
    }

    public byte[] readAll(String objectKey) {
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(config.bucket()).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "对象不存在");
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("storage_get_failed key={} err={}", KeyRef.of(objectKey), e.toString());
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储读取失败");
        }
    }

    /** 按字节范围回读，用于大文件引用预览，避免整文件载入内存。 */
    public byte[] readRange(String objectKey, long offset, int length) {
        if (offset < 0 || length <= 0) {
            return new byte[0];
        }
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(config.bucket()).object(objectKey).offset(offset).length((long) length).build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "对象不存在");
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储读取失败");
        }
    }

    public long sizeOf(String objectKey) {
        try {
            return client.statObject(StatObjectArgs.builder()
                    .bucket(config.bucket()).object(objectKey).build()).size();
        } catch (ErrorResponseException e) {
            return -1;
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储不可用");
        }
    }

    public boolean exists(String objectKey) {
        return sizeOf(objectKey) >= 0;
    }

    /** 删除走保留策略由运维执行；这里只负责物理删除并报告是否成功。 */
    public boolean delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(config.bucket()).object(objectKey).build());
            return !exists(objectKey);
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("storage_delete_failed key={} err={}", KeyRef.of(objectKey), e.toString());
            return false;
        }
    }

    public void assertBucketReady() {
        try {
            boolean found = client.bucketExists(BucketExistsArgs.builder().bucket(config.bucket()).build());
            if (!found) {
                throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE,
                        "对象存储桶缺失，需先运行 compose 初始化");
            }
        } catch (MinioException | IOException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "对象存储不可用");
        }
    }
}
