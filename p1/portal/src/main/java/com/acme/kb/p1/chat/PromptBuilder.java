package com.acme.kb.p1.chat;

import com.acme.kb.p1.engine.KnowledgeEnginePort;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 组装：每条依据以 [n] 编号，编号与引用记录一一对应（used 始终是 candidates 的前缀，
 * 所以引用不会出现断号）。预算按分数从高到低贪心填充，第一条装不下即停止——
 * 宁可少给依据，也不跳过中间片段拼一条"看起来证据很多"的回答。
 * 单条块文本另有 1400 字符上限（> chunk-size 800，正常切分下不会触发），
 * 只为兜住外部引擎返回超长块时挤掉其余全部引用的情况。
 */
public final class PromptBuilder {

    private static final String SYSTEM = """
            你是企业内部知识库助手。只能依据给出的【依据片段】回答，禁止使用片段之外的知识，禁止编造。
            每个论断后面用 [n] 标注它所依据的片段编号；若片段不足以回答，只输出 NO_ANSWER，不要猜测，不要编造引用。
            用简体中文回答，简洁、可核对。""";

    private PromptBuilder() {
    }

    public record Built(String system, String user, List<KnowledgeEnginePort.RetrievedChunk> used,
                        int budgetChars) {
    }

    public static Built build(String question, List<KnowledgeEnginePort.RetrievedChunk> candidates,
                              int maxChars) {
        int budget = Math.max(600, maxChars);
        StringBuilder user = new StringBuilder();
        List<KnowledgeEnginePort.RetrievedChunk> used = new ArrayList<>();
        String tail = "\n\n问题：" + question + "\n请直接给出结论与依据编号。";
        int room = budget - SYSTEM.length() - tail.length() - 32;
        user.append("【依据片段】\n");
        int cost = user.length();
        for (int i = 0; i < candidates.size(); i++) {
            KnowledgeEnginePort.RetrievedChunk chunk = candidates.get(i);
            String block = format(i + 1, chunk);
            if (cost + block.length() > room) {
                break;
            }
            user.append(block);
            cost += block.length();
            used.add(chunk);
        }
        user.append(tail);
        return new Built(SYSTEM, user.toString(), used, budget);
    }

    private static String format(int index, KnowledgeEnginePort.RetrievedChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(index).append("] ");
        if (chunk.section() != null && !chunk.section().isBlank()) {
            sb.append(chunk.section().strip()).append(" · ");
        }
        if (chunk.pageNo() != null) {
            sb.append("第").append(chunk.pageNo()).append("页 · ");
        }
        sb.append("字符 ").append(chunk.charStart() == null ? 0 : chunk.charStart())
                .append('-').append(chunk.charEnd() == null ? 0 : chunk.charEnd()).append('\n');
        sb.append(truncate(chunk.content(), 1400)).append("\n\n");
        return sb.toString();
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}
