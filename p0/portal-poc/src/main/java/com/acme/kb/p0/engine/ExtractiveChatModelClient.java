package com.acme.kb.p0.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 抽取式回退：不做生成，只按证据原文摘录作答。
 * 用于模型不可用时的降级路径与链路测试；答案里会显式标注，评测报告不得把它当作模型质量结果。
 */
public class ExtractiveChatModelClient implements ChatModelClient {

    private static final int MAX_SENTENCES = 5;

    @Override
    public String model() {
        return "extractive-fallback";
    }

    @Override
    public String kind() {
        return "extractive";
    }

    @Override
    public Completion stream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        long begin = System.nanoTime();
        List<String> lines = new ArrayList<>();
        for (String raw : userPrompt.split("\n")) {
            String line = raw.strip();
            if (line.length() > 24 && (line.contains("。") || line.contains(". "))) {
                lines.add(line);
                if (lines.size() >= MAX_SENTENCES) {
                    break;
                }
            }
        }
        String answer = "（抽取式回退，未使用生成模型）依据片段摘录：\n"
                + String.join("\n", lines.isEmpty() ? List.of("（证据片段过短，无法摘录）") : lines);
        long firstTokenMs = (System.nanoTime() - begin) / 1_000_000;
        onToken.accept(answer);
        long totalMs = (System.nanoTime() - begin) / 1_000_000;
        return new Completion(answer, model(), Math.max(firstTokenMs, 0), totalMs,
                Chunk.estimateTokens(userPrompt), Chunk.estimateTokens(answer));
    }
}
