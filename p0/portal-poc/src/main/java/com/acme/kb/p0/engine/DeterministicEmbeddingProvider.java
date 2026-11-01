package com.acme.kb.p0.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性哈希向量（特征哈希）。只在模型不可用时用于链路/契约验证，
 * 绝不能作为检索质量结论的依据——评测报告会打印 kind 以示区分。
 */
public class DeterministicEmbeddingProvider implements EmbeddingProvider {

    private final int dim;
    private final String model;

    public DeterministicEmbeddingProvider(int dim) {
        this.dim = Math.max(32, dim);
        this.model = "hash-ngram-" + this.dim;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String kind() {
        return "deterministic-fallback";
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        for (String feature : features(text)) {
            int h = feature.hashCode();
            int idx = Math.floorMod(h, dim);
            v[idx] += (h & 1) == 0 ? 1f : -1f;
        }
        return OllamaEmbeddingProvider.normalize(v);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(embed(t));
        }
        return out;
    }

    private List<String> features(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        List<String> feats = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean cjk = c >= 0x4E00 && c <= 0x9FFF;
            if (cjk) {
                if (!word.isEmpty()) {
                    feats.add(word.toString());
                    word.setLength(0);
                }
                if (i + 1 < s.length()) {
                    feats.add(s.substring(i, i + 2));
                }
                feats.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (!word.isEmpty()) {
                feats.add(word.toString());
                word.setLength(0);
            }
        }
        if (!word.isEmpty()) {
            feats.add(word.toString());
        }
        return feats;
    }
}
