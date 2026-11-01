package com.acme.kb.p1.common;

import org.springframework.http.HttpStatus;

/**
 * 冻结的业务错误码，与 contracts/openapi.yaml 的 ApiError.code 一一对应。
 * message 面向用户，绝不包含对象键、Prompt、凭据或堆栈。
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    CROSS_TENANT_ACCESS(HttpStatus.FORBIDDEN),
    CLEARANCE_TOO_LOW(HttpStatus.FORBIDDEN),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    TASK_NOT_RETRYABLE(HttpStatus.CONFLICT),
    DOCUMENT_FORMAT_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY),
    DOCUMENT_SIGNATURE_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY),
    CHAT_NO_EVIDENCE(HttpStatus.UNPROCESSABLE_ENTITY),
    ENGINE_INDEX_FAILED(HttpStatus.BAD_GATEWAY),
    ENGINE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    ENGINE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    DOCUMENT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    CONCURRENCY_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    CSRF_INVALID(HttpStatus.FORBIDDEN),
    AUDIT_WRITE_FAILED(HttpStatus.SERVICE_UNAVAILABLE),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    CONVERSATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    WIKI_SOURCE_UNAVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY),
    WIKI_REVIEW_SELF_APPROVAL(HttpStatus.FORBIDDEN),
    WIKI_REVIEWER_NOT_ASSIGNED(HttpStatus.FORBIDDEN),
    WIKI_REVISION_LOST(HttpStatus.CONFLICT),
    WIKI_TITLE_INVALID(HttpStatus.BAD_REQUEST),
    WIKI_BODY_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** 永久性错误：不进入重试主题，直接置 FAILED 并要求用户修复。 */
    public boolean permanent() {
        return this == DOCUMENT_FORMAT_UNSUPPORTED
                || this == DOCUMENT_SIGNATURE_MISMATCH
                || this == VALIDATION_FAILED
                || this == CROSS_TENANT_ACCESS
                || this == ACCESS_DENIED
                // P2 Wiki 治理：这几类都要人来修，重放只会再产出同一失败（state-machine-wiki.md §4）
                || this == WIKI_SOURCE_UNAVAILABLE
                || this == WIKI_REVIEW_SELF_APPROVAL
                || this == WIKI_REVIEWER_NOT_ASSIGNED
                || this == WIKI_TITLE_INVALID
                || this == WIKI_BODY_TOO_LARGE;
    }

    /** 暂时性错误：可退避重试，超过上限进 DLQ。 */
    public boolean transientFailure() {
        return this == ENGINE_TIMEOUT || this == ENGINE_UNAVAILABLE
                || this == ENGINE_INDEX_FAILED || this == STORAGE_UNAVAILABLE
                || this == MODEL_UNAVAILABLE || this == CONCURRENCY_LIMITED;
    }

    public static ErrorCode parse(String raw, ErrorCode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
