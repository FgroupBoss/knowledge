package com.acme.kb.p1.common;

import java.util.List;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final List<Map<String, String>> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, List.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, String> detail) {
        this(code, message, List.of(detail));
    }

    public ApiException(ErrorCode code, String message, List<Map<String, String>> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public List<Map<String, String>> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, what + "不存在");
    }
}
