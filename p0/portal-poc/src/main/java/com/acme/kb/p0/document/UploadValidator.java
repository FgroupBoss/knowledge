package com.acme.kb.p0.document;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.config.P0Properties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 上传边界校验：文件名只用于显示，存储键由服务端生成；
 * 扩展名/MIME/文件签名三重校验 + 大小上限 + 并发上限，先落临时文件再入对象存储。
 */
@Component
public class UploadValidator {

    private static final Logger log = LoggerFactory.getLogger(UploadValidator.class);

    private final P0Properties props;
    private Semaphore slots;
    private Set<String> extensions;
    private Set<String> mimeTypes;

    public UploadValidator(P0Properties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        slots = new Semaphore(Math.max(1, props.upload().maxConcurrent()));
        extensions = new HashSet<>(props.upload().allowedExtensions());
        mimeTypes = new HashSet<>(props.upload().allowedMimeTypes());
        Path tmp = Path.of(props.tempDir());
        try {
            Files.createDirectories(tmp);
        } catch (IOException e) {
            throw new IllegalStateException("临时目录不可用: " + tmp.toAbsolutePath(), e);
        }
    }

    public record Spool(Path file, long size, String sha256, String extension, String mimeType,
                        String displayName) {

        public void discard() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.debug("temp_cleanup_failed {}", file);
            }
        }
    }

    public Spool spool(MultipartFile file, String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "上传内容为空");
        }
        if (file.getSize() > props.upload().maxBytes()) {
            throw new ApiException(ErrorCode.DOCUMENT_TOO_LARGE,
                    "文件超过上限 " + props.upload().maxBytes() + " 字节");
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.CONCURRENCY_LIMITED, "上传通道繁忙");
        }
        if (!acquired) {
            throw new ApiException(ErrorCode.CONCURRENCY_LIMITED,
                    "并发上传已达上限 " + props.upload().maxConcurrent());
        }
        try {
            return doSpool(file);
        } finally {
            slots.release();
        }
    }

    private Spool doSpool(MultipartFile file) {
        String displayName = safeName(file.getOriginalFilename());
        String ext = extensionOf(displayName);
        if (!extensions.contains(ext)) {
            throw new ApiException(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED, "扩展名不在允许清单: " + ext);
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (declared.isBlank()) {
            declared = mimeFor(ext);
        }
        if (!declared.equals("application/octet-stream")) {
            if (!mimeTypes.contains(declared)) {
                throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "声明的 MIME 不在允许清单");
            }
            // 声明 MIME 必须与扩展名相容；文本类允许统一声明为 text/plain
            if (!declared.equals(mimeFor(ext)) && !(isTextLike(ext) && declared.equals("text/plain"))) {
                throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "MIME 与扩展名不一致");
            }
        }
        Path target;
        try {
            target = Files.createTempFile(Path.of(props.tempDir()), "p0-upload-", ".bin");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "无法准备临时空间");
        }
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(target);
            if (size == 0 || size > props.upload().maxBytes()) {
                throw new ApiException(ErrorCode.DOCUMENT_TOO_LARGE, "文件为空或超过上限");
            }
            verifySignature(target, ext);
            String sha;
            try (InputStream in = Files.newInputStream(target)) {
                sha = Hashing.sha256Hex(in);
            }
            return new Spool(target, size, sha, ext, declared, displayName);
        } catch (ApiException e) {
            discardQuietly(target);
            throw e;
        } catch (IOException e) {
            discardQuietly(target);
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "无法读取上传内容");
        }
    }

    /** 文件签名校验：防止改名绕过扩展名清单。 */
    private void verifySignature(Path file, String ext) throws IOException {
        byte[] head = new byte[8];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, head.length);
        }
        if (read < head.length && !isTextLike(ext)) {
            throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "文件过小，签名不完整");
        }
        switch (ext) {
            case "pdf" -> {
                if (!new String(head, 0, Math.min(read, 5), java.nio.charset.StandardCharsets.US_ASCII)
                        .equals("%PDF-")) {
                    throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "不是有效 PDF");
                }
            }
            case "docx", "xlsx", "pptx" -> {
                if (!(head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5)
                        && (head[3] == 4 || head[3] == 6))) {
                    throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH, "不是有效 OOXML 包");
                }
            }
            default -> {
                for (int i = 0; i < read; i++) {
                    if (head[i] == 0) {
                        throw new ApiException(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH,
                                "文本类扩展名但含二进制内容");
                    }
                }
            }
        }
    }

    private static boolean isTextLike(String ext) {
        return switch (ext) {
            case "md", "markdown", "txt", "log", "csv", "tsv", "json" -> true;
            default -> false;
        };
    }

    private static String mimeFor(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "tsv" -> "text/tab-separated-values";
            case "md", "markdown" -> "text/markdown";
            default -> "text/plain";
        };
    }

    public static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少 Idempotency-Key");
        }
        try {
            UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必须是 UUID");
        }
    }

    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed.txt";
        }
        String base = Path.of(original.replace('\\', '/')).getFileName().toString();
        String cleaned = base.replaceAll("[\\p{Cntrl}\\[\\]]", "_");
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void discardQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件由操作系统回收，忽略
        }
    }
}
