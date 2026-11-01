package com.acme.kb.p1.engine;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/** 本地 Ollama /api/embed。禁止任何云端回退：地址固定为内网/本机，超时即失败。 */
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final Semaphore permits;

    public OllamaEmbeddingProvider(HttpClient http, ObjectMapper mapper, String baseUrl, String model,
                                   long timeoutMs, int maxConcurrent) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String kind() {
        return "ollama";
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        ArrayNode input = req.putArray("input");
        texts.forEach(input::add);
        String body;
        try {
            body = mapper.writeValueAsString(req);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "向量请求序列化失败");
        }
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ApiException(ErrorCode.CONCURRENCY_LIMITED, "模型并发已满，请稍后重试");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "向量服务返回 " + response.statusCode());
            }
            JsonNode node = mapper.readTree(response.body());
            JsonNode embeddings = node.path("embeddings");
            if (!embeddings.isArray() || embeddings.size() != texts.size()) {
                throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "向量响应格式不符合预期");
            }
            List<float[]> out = new ArrayList<>(embeddings.size());
            for (JsonNode vec : embeddings) {
                float[] v = new float[vec.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) vec.get(i).asDouble();
                }
                out.add(normalize(v));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "向量服务超时或不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "向量请求被中断");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float f : v) {
            sum += (double) f * f;
        }
        if (sum == 0) {
            return v;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
