package com.acme.kb.p1.chat;

import com.acme.kb.p1.engine.KnowledgeEnginePort.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prompt 预算必须“宁可少给证据，也不跳号拼接”。 */
class PromptBuilderTest {

    private static RetrievedChunk chunk(String id, int chars, double score) {
        return new RetrievedChunk("c-" + id, "d-" + id, "ekb", "t1", "w1", "kb1", "doc-" + id, 1,
                "INTERNAL", 3, "节", 0, chars, "证".repeat(chars), score);
    }

    @Test
    void usedChunksAreAlwaysAPrefixOfCandidates() {
        List<RetrievedChunk> candidates = List.of(
                chunk("1", 200, 0.9), chunk("2", 200, 0.8),
                chunk("3", 200, 0.7), chunk("4", 200, 0.6));
        PromptBuilder.Built built = PromptBuilder.build("问题是什么？", candidates, 900);

        List<String> usedIds = built.used().stream().map(RetrievedChunk::engineChunkId).toList();
        List<String> candidateIds = candidates.stream().map(RetrievedChunk::engineChunkId).toList();
        assertEquals(candidateIds.subList(0, usedIds.size()), usedIds);
        assertFalse(usedIds.isEmpty());

        for (int i = 0; i < usedIds.size(); i++) {
            assertTrue(built.user().contains("[" + (i + 1) + "]"), "编号必须连续");
        }
        assertTrue(built.user().contains("问题是什么？"));
        assertEquals(900, built.budgetChars());
    }

    @Test
    void stopsInsteadOfSkippingToSmallerChunk() {
        List<RetrievedChunk> candidates = List.of(
                chunk("big", 5000, 0.9), chunk("small", 20, 0.8));
        PromptBuilder.Built built = PromptBuilder.build("q", candidates, 800);
        // 第一条超预算直接停止：不能跳过它去装第二条，否则引用编号与证据顺序脱节
        assertTrue(built.used().isEmpty());
        assertFalse(built.user().contains("[2]"));
    }

    @Test
    void truncateHandlesNullAndLimitsLength() {
        assertEquals("", PromptBuilder.truncate(null, 10));
        assertEquals("abc", PromptBuilder.truncate("  abc  ", 10));
        assertEquals(10, PromptBuilder.truncate("x".repeat(100), 10).length());
    }
}
