package com.acme.kb.p1.engine;

import java.util.List;

public interface EmbeddingProvider {

    String model();

    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);

    /** 供 engineProbe 与评测报告说明向量来源（真实模型或确定性回退）。 */
    String kind();
}
