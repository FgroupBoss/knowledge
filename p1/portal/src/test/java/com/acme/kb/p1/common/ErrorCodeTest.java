package com.acme.kb.p1.common;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 错误码分区是退避策略的输入，必须互斥且可稳定解析。 */
class ErrorCodeTest {

    /** `p1/contracts/state-machine-wiki.md` §4 的永久清单 + `p0/contracts/state-machine.md` 的 P1 清单。 */
    private static final Set<ErrorCode> CONTRACT_PERMANENT = Set.of(
            ErrorCode.VALIDATION_FAILED, ErrorCode.ACCESS_DENIED, ErrorCode.CROSS_TENANT_ACCESS,
            ErrorCode.DOCUMENT_FORMAT_UNSUPPORTED, ErrorCode.DOCUMENT_SIGNATURE_MISMATCH,
            ErrorCode.WIKI_SOURCE_UNAVAILABLE, ErrorCode.WIKI_REVIEW_SELF_APPROVAL,
            ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED, ErrorCode.WIKI_TITLE_INVALID, ErrorCode.WIKI_BODY_TOO_LARGE);

    private static final Set<ErrorCode> CONTRACT_TRANSIENT = Set.of(
            ErrorCode.ENGINE_TIMEOUT, ErrorCode.ENGINE_UNAVAILABLE, ErrorCode.ENGINE_INDEX_FAILED,
            ErrorCode.STORAGE_UNAVAILABLE, ErrorCode.MODEL_UNAVAILABLE, ErrorCode.CONCURRENCY_LIMITED);

    @Test
    void permanentAndTransientAreDisjoint() {
        for (ErrorCode code : ErrorCode.values()) {
            assertFalse(code.permanent() && code.transientFailure(), code + " 不能同时是永久与暂时错误");
        }
    }

    /** 分区漂移=退避策略漂移，所以这里按清单逐字比对，而不是只抽查几个。 */
    @Test
    void permanentAndTransientSetsAreExactlyTheContractLists() {
        Set<ErrorCode> permanent = new HashSet<>();
        Set<ErrorCode> transientSet = new HashSet<>();
        Arrays.stream(ErrorCode.values()).forEach(code -> {
            if (code.permanent()) {
                permanent.add(code);
            }
            if (code.transientFailure()) {
                transientSet.add(code);
            }
        });
        assertEquals(CONTRACT_PERMANENT, permanent);
        assertEquals(CONTRACT_TRANSIENT, transientSet);
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

    /** P2：治理类失败要人来修，重放只会再产出同一个失败（§4，D-04）。 */
    @Test
    void wikiFailuresNeedingAHumanArePermanent() {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.WIKI_SOURCE_UNAVAILABLE,
                ErrorCode.WIKI_REVIEW_SELF_APPROVAL, ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED,
                ErrorCode.WIKI_TITLE_INVALID, ErrorCode.WIKI_BODY_TOO_LARGE}) {
            assertTrue(code.permanent(), code + " 必须是永久错误");
            assertFalse(code.transientFailure(), code + " 不该进退避重试");
        }
    }

    /** 乐观锁与状态冲突既不是永久也不是暂时：客户端重试要带新的 If-Match，不能由服务端盲目重放。 */
    @Test
    void conflictsAreNeitherPermanentNorTransient() {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.WIKI_REVISION_LOST, ErrorCode.VERSION_CONFLICT,
                ErrorCode.INVALID_STATE_TRANSITION, ErrorCode.IDEMPOTENCY_CONFLICT}) {
            assertFalse(code.permanent(), code + " 不该被归为永久");
            assertFalse(code.transientFailure(), code + " 不该被归为暂时");
        }
    }

    @Test
    void wikiHttpStatusesMatchTheErrorCodeTable() {
        assertEquals(422, ErrorCode.WIKI_SOURCE_UNAVAILABLE.status().value());
        assertEquals(403, ErrorCode.WIKI_REVIEW_SELF_APPROVAL.status().value());
        assertEquals(403, ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.status().value());
        assertEquals(409, ErrorCode.WIKI_REVISION_LOST.status().value());
        assertEquals(400, ErrorCode.WIKI_TITLE_INVALID.status().value());
        assertEquals(413, ErrorCode.WIKI_BODY_TOO_LARGE.status().value());
    }

    @Test
    void parseFallsBackOnUnknownOrBlank() {
        assertEquals(ErrorCode.ENGINE_UNAVAILABLE, ErrorCode.parse("NOT_A_CODE", ErrorCode.ENGINE_UNAVAILABLE));
        assertEquals(ErrorCode.ENGINE_UNAVAILABLE, ErrorCode.parse("  ", ErrorCode.ENGINE_UNAVAILABLE));
        assertEquals(ErrorCode.ENGINE_TIMEOUT, ErrorCode.parse("ENGINE_TIMEOUT", ErrorCode.ENGINE_UNAVAILABLE));
    }
}
