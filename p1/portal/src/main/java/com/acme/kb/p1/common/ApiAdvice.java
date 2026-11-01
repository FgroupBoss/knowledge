package com.acme.kb.p1.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一错误结构 {code,message,requestId,details}（契约 §7）。
 * 未预期异常只回 INTERNAL_ERROR + traceId，堆栈仅进日志。
 */
@RestControllerAdvice
public class ApiAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiAdvice.class);

    public record ApiError(String code, String message, String requestId, String traceId,
                           List<Map<String, String>> details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> onApiException(ApiException e, HttpServletRequest req) {
        HttpStatus status = e.code().status();
        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            log.error("internal error at {} {}", req.getMethod(), req.getRequestURI(), e);
        } else {
            log.debug("rejected {} {} code={}", req.getMethod(), req.getRequestURI(), e.code());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder = builder.header(HttpHeaders.RETRY_AFTER,
                    String.valueOf(retryAfterSeconds(e.code())));
        }
        return builder.body(body(e.code(), e.getMessage(), e.details(), req));
    }

    /**
     * 问答限流是固定分钟窗口，因此能给出真实的重试时刻；
     * 并发槽位（上传/模型信号量）只能给出保守提示，不假装有精确值。
     */
    private static long retryAfterSeconds(ErrorCode code) {
        if (code == ErrorCode.RATE_LIMITED) {
            long nowSeconds = System.currentTimeMillis() / 1000L;
            return Math.max(1, (nowSeconds / 60 + 1) * 60 - nowSeconds);
        }
        return 5;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> details = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f ->
                details.add(Map.of("field", f.getField(), "reason", String.valueOf(f.getDefaultMessage()))));
        return ResponseEntity.badRequest().body(
                body(ErrorCode.VALIDATION_FAILED, "请求参数不合法", details, req));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiError> onBadRequest(Exception e, HttpServletRequest req) {
        return ResponseEntity.badRequest().body(
                body(ErrorCode.VALIDATION_FAILED, "请求参数不合法", List.of(), req));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> onTooLarge(MaxUploadSizeExceededException e, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
                body(ErrorCode.DOCUMENT_TOO_LARGE, "文件超过大小上限", List.of(), req));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> onConflict(DataIntegrityViolationException e, HttpServletRequest req) {
        log.warn("integrity violation at {}", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                body(ErrorCode.IDEMPOTENCY_CONFLICT, "资源状态冲突，请重试", List.of(), req));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled error at {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                body(ErrorCode.INTERNAL_ERROR, "服务暂时不可用", List.of(), req));
    }

    private ApiError body(ErrorCode code, String message, List<Map<String, String>> details,
                          HttpServletRequest req) {
        Object requestId = req.getAttribute(TraceIds.ATTR_REQUEST_ID);
        Object traceId = req.getAttribute(TraceIds.ATTR_TRACE_ID);
        String rid = requestId == null ? TraceIds.newOpaque() : requestId.toString();
        return new ApiError(code.name(), message, rid, traceId == null ? rid : traceId.toString(),
                details == null || details.isEmpty() ? null : details);
    }

    /** 供控制器复用同一结构。 */
    public static Map<String, Object> errorMap(ErrorCode code, String message, String requestId, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code.name());
        m.put("message", message);
        m.put("requestId", requestId);
        m.put("traceId", traceId);
        return m;
    }
}
