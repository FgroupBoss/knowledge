package com.acme.kb.p1.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒答哨兵是“零引用”的判定依据：只要模型输出 NO_ANSWER 就必须落 REFUSED，
 * 不允许把拒答文本当 ANSWERED 写进引用表。
 */
class ChatServiceRefusalTest {

    @Test
    void emptyOrNullIsRefusal() {
        assertTrue(ChatService.isRefusal(null));
        assertTrue(ChatService.isRefusal(""));
        assertTrue(ChatService.isRefusal("   \n "));
    }

    @Test
    void sentinelAtHeadIsRefusalEvenWithExplanation() {
        assertTrue(ChatService.isRefusal("NO_ANSWER"));
        assertTrue(ChatService.isRefusal("  NO_ANSWER 依据不足"));
        assertTrue(ChatService.isRefusal("no_answer：片段没有相关信息"));
    }

    @Test
    void sentinelInsideAnswerIsNotARefusal() {
        assertFalse(ChatService.isRefusal("结论见 [1]，未出现 NO_ANSWER 字样"));
        assertFalse(ChatService.isRefusal("按制度应在 3 日内完成审批 [2]。"));
    }
}
