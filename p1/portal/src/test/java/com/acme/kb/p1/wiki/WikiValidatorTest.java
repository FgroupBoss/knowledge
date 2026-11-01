package com.acme.kb.p1.wiki;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 校验器的分类必须与 `p1/contracts/error-codes.md` 一致：同一入口里"标题问题"和"超限问题"
 * 走不同码，因为客户端的后续动作不同（改标题 vs 拆页面）。多出或写错码会被 24 号脚本判"声明不可产生"。
 */
class WikiValidatorTest {

    @Test
    void titleRulesUseTheDedicatedCode() {
        WikiValidator v = validator(WikiValidator.TITLE_MAX_CHARS * 4);
        assertEquals(ErrorCode.WIKI_TITLE_INVALID, assertThrows(ApiException.class, () -> v.requireTitle(null)).code());
        assertEquals(ErrorCode.WIKI_TITLE_INVALID, assertThrows(ApiException.class, () -> v.requireTitle("   ")).code());
        assertEquals(ErrorCode.WIKI_TITLE_INVALID,
                assertThrows(ApiException.class, () -> v.requireTitle("字".repeat(WikiValidator.TITLE_MAX_CHARS + 1))).code());
        assertEquals(ErrorCode.WIKI_TITLE_INVALID,
                assertThrows(ApiException.class, () -> v.requireTitle("含\t制表符的标题")).code());
        assertDoesNotThrow(() -> v.requireTitle("字".repeat(WikiValidator.TITLE_MAX_CHARS)));
    }

    /** 标题消息不能带正文语义，只写限制值——审计与日志都不落正文（红线 1）。 */
    @Test
    void titleMessageNeverEchoesTheTitle() {
        WikiValidator v = validator(1024);
        String secret = "只有我知道的标题";
        ApiException e = assertThrows(ApiException.class, () -> v.requireTitle(secret + "\n注入"));
        assertFalseContains(e.getMessage(), secret);
    }

    @Test
    void slugAcceptsOnlyLowercaseHyphenatedForms() {
        WikiValidator v = validator(1024);
        for (String ok : new String[]{"ab", "a1", "run-book", "kb-2026-adr-012"}) {
            assertDoesNotThrow(() -> v.requireSlug(ok), ok + " 应合法");
        }
        for (String bad : new String[]{null, "", "a", "Ab", "a_b", "-ab", "a b", "ab".repeat(3) + "x".repeat(80)}) {
            assertEquals(ErrorCode.VALIDATION_FAILED,
                    assertThrows(ApiException.class, () -> v.requireSlug(bad)).code(), String.valueOf(bad));
        }
        assertDoesNotThrow(() -> v.requireSlug("a" + "b".repeat(79)));
    }

    /** 正文按 UTF-8 字节计，不按字符计：中文页面若按字符数限，200 KiB 上限会放过 600 KiB 真实体积。 */
    @Test
    void bodyLimitIsMeasuredInBytesNotCharacters() {
        WikiValidator v = validator(8);
        assertDoesNotThrow(() -> v.requireBody("abcdefgh"));
        assertDoesNotThrow(() -> v.requireBody("中文"));
        assertEquals(ErrorCode.WIKI_BODY_TOO_LARGE,
                assertThrows(ApiException.class, () -> v.requireBody("中文中")).code());
        assertEquals(ErrorCode.WIKI_BODY_TOO_LARGE,
                assertThrows(ApiException.class, () -> v.requireBody("abcdefghi")).code());
    }

    @Test
    void emptyBodyIsAllowedButOverLimitIsNot() {
        WikiValidator v = validator(8);
        assertDoesNotThrow(() -> v.requireBody(null));
        assertDoesNotThrow(() -> v.requireBody(""));
        assertEquals(8, v.maxBodyBytes());
    }

    @Test
    void summaryLimitIsValidationFailed() {
        WikiValidator v = validator(1024);
        assertDoesNotThrow(() -> v.requireSummary(null));
        assertDoesNotThrow(() -> v.requireSummary("摘".repeat(WikiValidator.SUMMARY_MAX_CHARS)));
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(ApiException.class,
                        () -> v.requireSummary("摘".repeat(WikiValidator.SUMMARY_MAX_CHARS + 1))).code());
    }

    /** E2/R3 的前置校验：退回必须写理由，理由原文只落哈希，所以这里只判长度与空值。 */
    @Test
    void decisionNoteIsRequiredForReject() {
        WikiValidator v = validator(1024);
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class, () -> v.requireDecisionNote(null)).code());
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class, () -> v.requireDecisionNote("  ")).code());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(ApiException.class,
                        () -> v.requireDecisionNote("由".repeat(WikiValidator.DECISION_NOTE_MAX_CHARS + 1))).code());
        assertDoesNotThrow(() -> v.requireDecisionNote("依据不足，请补充来源"));
    }

    @Test
    void codesMapToTheContractedHttpStatuses() {
        assertEquals(400, ErrorCode.WIKI_TITLE_INVALID.status().value());
        assertEquals(413, ErrorCode.WIKI_BODY_TOO_LARGE.status().value());
        assertEquals(422, ErrorCode.WIKI_SOURCE_UNAVAILABLE.status().value());
        assertEquals(403, ErrorCode.WIKI_REVIEW_SELF_APPROVAL.status().value());
        assertEquals(403, ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.status().value());
        assertEquals(409, ErrorCode.WIKI_REVISION_LOST.status().value());
    }

    private static void assertFalseContains(String message, String secret) {
        if (message != null && message.contains(secret)) {
            throw new AssertionError("错误消息回显了用户输入：" + message);
        }
    }

    private static WikiValidator validator(int maxBodyBytes) {
        return new WikiValidator(new KbProperties("unit", "unit", false, "unit", null, null, null, null,
                null, null, null, null, null, null, null, null, wiki(maxBodyBytes)));
    }

    private static KbProperties.Wiki wiki(int maxBodyBytes) {
        return new KbProperties.Wiki(true, maxBodyBytes, 2, 5000L, 600000L, 6, 45, 15, 14, 0, 72,
                "0 30 2 * * *", 180, "kb.wiki.sync.v1", "kb.wiki.retract.v1", "kb.wiki.dlq.v1",
                "p1-wiki-sync", "p1-wiki-retract");
    }
}
