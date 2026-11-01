package com.acme.kb.p1.engine;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Ollama /api/chat 流式调用。thinking 关闭以保证首 Token 语义为正文首字。 */
public class OllamaChatModelClient implements ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatModelClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final Semaphore permits;
    private final int maxNewTokens;
    private final double temperature;

    public OllamaChatModelClient(HttpClient http, ObjectMapper mapper, String baseUrl, String model,
                                 long timeoutMs, int maxConcurrent, int maxNewTokens, double temperature) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.permits = new Semaphore(Math.max(1, maxConcurrent));
        this.maxNewTokens = maxNewTokens;
        this.temperature = temperature;
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
    public Completion stream(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", model);
        req.put("stream", true);
        req.put("think", false);
        ObjectNode options = req.putObject("options");
        options.put("num_predict", maxNewTokens);
        options.put("temperature", temperature);
        ArrayNode messages = req.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt);

        long begin = System.nanoTime();
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ApiException(ErrorCode.CONCURRENCY_LIMITED, "模型并发已满，请稍后重试");
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req),
                            StandardCharsets.UTF_8))
                    .build();
            HttpResponse<java.io.InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "模型服务返回 " + response.statusCode());
            }
            StringBuilder full = new StringBuilder();
            long firstTokenMs = -1;
            Integer promptTokens = null;
            Integer completionTokens = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = mapper.readTree(line);
                    if (node.hasNonNull("error")) {
                        throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "模型服务错误");
                    }
                    String delta = node.path("message").path("content").asText("");
                    if (!delta.isEmpty()) {
                        if (firstTokenMs < 0) {
                            firstTokenMs = (System.nanoTime() - begin) / 1_000_000;
                        }
                        full.append(delta);
                        onToken.accept(delta);
                    }
                    if (node.path("done").asBoolean(false)) {
                        promptTokens = node.hasNonNull("prompt_eval_count")
                                ? node.get("prompt_eval_count").asInt() : null;
                        completionTokens = node.hasNonNull("eval_count")
                                ? node.get("eval_count").asInt() : null;
                        break;
                    }
                }
            }
            long totalMs = (System.nanoTime() - begin) / 1_000_000;
            if (full.isEmpty()) {
                throw new ApiException(ErrorCode.MODEL_UNAVAILABLE, "模型未产出正文");
            }
            return new Completion(full.toString(), model, Math.max(firstTokenMs, 0), totalMs,
                    promptTokens, completionTokens);
        } catch (ApiException e) {
            throw e;
        } catch (java.io.IOException e) {
            log.warn("chat_failed err={}", e.toString());
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "模型服务超时或不可达");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.ENGINE_TIMEOUT, "模型请求被中断");
        } finally {
            if (acquired) {
                permits.release();
            }
        }
    }
}
