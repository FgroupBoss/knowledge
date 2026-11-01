package com.acme.kb.p1.engine;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.config.KbProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 适配器契约测试：用本地 HTTP 桩验证“越界候选丢弃、元数据不全丢弃、tenant 过滤随请求下发”。
 * 这不代表 WeKnora 真镜像已验证——那一层只由能力探测门禁回答。
 */
class WeKnoraAdapterTest {

    private HttpServer server;
    private WeKnoraAdapter adapter;
    private final AtomicReference<String> lastSearchBody = new AtomicReference<>();
    private volatile int searchStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/search", exchange -> {
            lastSearchBody.set(read(exchange));
            String body = """
                    {"data":{"items":[
                      {"chunk_id":"c1","content":"范围内的制度条款","score":0.71,
                       "metadata":{"tenant_id":"t1","workspace_id":"w1","kb_id":"kb1",
                         "document_id":"d1","engine_kb_id":"ekb1","document_version":3,
                         "classification":"INTERNAL","page_no":2,"section":"第3章",
                         "char_start":10,"char_end":40}},
                      {"chunk_id":"c2","content":"其他租户的内容","score":0.99,
                       "metadata":{"tenant_id":"t2","workspace_id":"w1","kb_id":"kb9",
                         "document_id":"d9","engine_kb_id":"ekb1","classification":"INTERNAL"}},
                      {"chunk_id":"c3","content":"缺少元数据","score":0.98,
                       "metadata":{"tenant_id":"t1"}}
                    ]}}
                    """;
            respond(exchange, searchStatus, body);
        });
        server.createContext("/api/v1/knowledge-bases", exchange ->
                respond(exchange, 200, "{\"data\":{\"id\":\"ekb-created\"}}"));
        server.createContext("/api/v1/knowledge-bases/documents/gone", exchange ->
                respond(exchange, 404, "{}"));
        server.start();

        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        KbProperties.Engine config = new KbProperties.Engine("weknora", base, 5000, 800, 120, 20, 6,
                0.18, false, false, "deterministic", "extractive", 256, null, null,
                "/api/v1/knowledge-bases", "/api/v1/search");
        adapter = new WeKnoraAdapter(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build(),
                new ObjectMapper(), config);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void searchKeepsOnlyInScopeCandidatesWithCompleteMetadata() {
        List<KnowledgeEnginePort.RetrievedChunk> chunks = adapter.search(new KnowledgeEnginePort.SearchQuery(
                "t1", List.of("ekb1"), "审批时限", 10, 3));

        assertEquals(1, chunks.size());
        KnowledgeEnginePort.RetrievedChunk only = chunks.get(0);
        assertEquals("c1", only.engineChunkId());
        assertEquals("ekb1", only.engineKbId());
        assertEquals("d1", only.documentId());
        assertEquals(3, only.documentVersion());
        assertEquals(2, only.pageNo());
        assertEquals("第3章", only.section());
        assertEquals(0.71, only.score(), 1e-9);

        String body = lastSearchBody.get();
        assertTrue(body.contains("\"tenant_id\":\"t1\""), "必须把租户过滤下发给引擎");
        assertTrue(body.contains("\"ekb1\""), "只允许检索已授权知识库");
    }

    @Test
    void emptyScopeNeverCallsEngine() {
        List<KnowledgeEnginePort.RetrievedChunk> chunks = adapter.search(new KnowledgeEnginePort.SearchQuery(
                "t1", List.of(), "q", 5, 1));
        assertTrue(chunks.isEmpty());
        assertEquals(null, lastSearchBody.get());
    }

    @Test
    void ensureKnowledgeBaseUnwrapsDataEnvelope() {
        String id = adapter.ensureKnowledgeBase(new KnowledgeEnginePort.EngineKbSpec(
                "kb1", "t1", "w1", "INTERNAL", 7));
        assertEquals("ekb-created", id);
    }

    @Test
    void deleteTreats404AsAlreadyGone() {
        assertTrue(adapter.deleteDocument("gone"));
    }

    @Test
    void engineErrorSurfacesAsUnavailable() {
        searchStatus = 500;
        ApiException error = assertThrows(ApiException.class, () -> adapter.search(
                new KnowledgeEnginePort.SearchQuery("t1", List.of("ekb1"), "q", 5, 1)));
        assertEquals(ErrorCode.ENGINE_UNAVAILABLE, error.code());
    }

    private static String read(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
