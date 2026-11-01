package com.acme.kb.p0.engine;

import java.util.function.Consumer;

public interface ChatModelClient {

    String model();

    String kind();

    /** 流式生成；onToken 只收到增量正文，首个增量到达的时刻即 firstTokenMs。 */
    Completion stream(String systemPrompt, String userPrompt, Consumer<String> onToken);

    default Completion generate(String systemPrompt, String userPrompt) {
        return stream(systemPrompt, userPrompt, s -> {
        });
    }

    record Completion(String text, String model, long firstTokenMs, long totalMs,
                      Integer promptTokens, Integer completionTokens) {
    }
}
