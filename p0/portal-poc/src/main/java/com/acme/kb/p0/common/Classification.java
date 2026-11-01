package com.acme.kb.p0.common;

/** 密级：主体 clearance 必须不低于知识库/文档密级。 */
public enum Classification {
    INTERNAL, SECRET, TOP_SECRET;

    public int rank() {
        return ordinal();
    }

    public boolean atLeast(Classification required) {
        return rank() >= required.rank();
    }

    public static Classification parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INTERNAL;
        }
        try {
            return valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "密级取值不合法");
        }
    }
}
