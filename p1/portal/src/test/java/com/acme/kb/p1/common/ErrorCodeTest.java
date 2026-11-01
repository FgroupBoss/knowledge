package com.acme.kb.p1.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 错误码分区是退避策略的输入，必须互斥且可稳定解析。 */
class ErrorCodeTest {

    @Test
    void permanentAndTransientAreDisjoint() {
        for (ErrorCode code : ErrorCode.values()) {
            assertFalse(code.permanent() && code.transientFailure(), code + " 不能同时是永久与暂时错误");
        }
    }

    @Test
    void formatAndSignatureFailuresArePermanent() {
        assertTrue(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED.permanent());
        assertTrue(ErrorCode.DOCUMENT_SIGNATURE_MISMATCH.permanent());
        assertFalse(ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED.transientFailure());
    }

    @Test
    void infrastructureFailuresAreTransient() {
        assertTrue(ErrorCode.ENGINE_UNAVAILABLE.transientFailure());
        assertTrue(ErrorCode.ENGINE_TIMEOUT.transientFailure());
        assertTrue(ErrorCode.STORAGE_UNAVAILABLE.transientFailure());
        assertTrue(ErrorCode.MODEL_UNAVAILABLE.transientFailure());
        assertTrue(ErrorCode.CONCURRENCY_LIMITED.transientFailure());
    }

    @Test
    void parseFallsBackOnUnknownOrBlank() {
        assertEquals(ErrorCode.ENGINE_UNAVAILABLE, ErrorCode.parse("NOT_A_CODE", ErrorCode.ENGINE_UNAVAILABLE));
        assertEquals(ErrorCode.ENGINE_UNAVAILABLE, ErrorCode.parse("  ", ErrorCode.ENGINE_UNAVAILABLE));
        assertEquals(ErrorCode.ENGINE_TIMEOUT, ErrorCode.parse("ENGINE_TIMEOUT", ErrorCode.ENGINE_UNAVAILABLE));
    }
}
