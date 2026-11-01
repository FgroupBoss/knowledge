package com.acme.kb.p0.chat;

import com.acme.kb.p0.api.Dto;
import com.acme.kb.p0.audit.AuditService;
import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.authz.AuthorizationService;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.Classification;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.common.TraceIds;
import com.acme.kb.p0.config.P0Properties;
import com.acme.kb.p0.engine.ChatModelClient;
import com.acme.kb.p0.engine.EngineAclSnapshot;
import com.acme.kb.p0.engine.KnowledgeEnginePort;
import com.acme.kb.p0.repo.ChatMapper;
import com.acme.kb.p0.repo.DocumentAclMapper;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.KnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 检索问答：授权范围在 Java 侧确定，引擎召回后逐条回 MySQL 复核，未通过复核的片段绝不进入 Prompt。
 * 引擎返回值一律按不可信输入处理；越权证据由 aclStats 计数留痕，正文不外泄。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_QUESTION_CHARS = 4000;
    private static final int SNIPPET_MAX_CHARS = 2000;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final String NO_EVIDENCE_ANSWER = "依据不足：在你当前有权访问的知识库范围内没有找到可核对的内容，"
            + "我不会用猜测补全答案。";

    private final AuthorizationService authorization;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;
    private final DocumentAclMapper documentAcls;
    private final ChatMapper store;
    private final KnowledgeEnginePort engine;
    private final ChatModelClient model;
    private final EngineAclSnapshot aclSnapshot;
    private final ChatRateLimiter limiter;
    private final AuditService audit;
    private final P0Properties props;

    public ChatService(AuthorizationService authorization, KnowledgeBaseMapper knowledgeBases,
                       DocumentMapper documents, DocumentAclMapper documentAcls, ChatMapper store,
                       KnowledgeEnginePort engine, ChatModelClient model, EngineAclSnapshot aclSnapshot,
                       ChatRateLimiter limiter, AuditService audit, P0Properties props) {
        this.authorization = authorization;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.documentAcls = documentAcls;
        this.store = store;
        this.engine = engine;
        this.model = model;
        this.aclSnapshot = aclSnapshot;
        this.limiter = limiter;
        this.audit = audit;
        this.props = props;
    }

    public Dto.ChatResponse ask(Actor actor, String kbId, Dto.ChatRequestDto request, String idempotencyKey) {
        Run run = begin(actor, kbId, request, idempotencyKey);
        if (run.replayed != null) {
            return run.replayed;
        }
        try {
            ChatModelClient.Completion completion = run.usable.isEmpty()
                    ? null : model.generate(run.prompt.system(), run.prompt.user());
            return finish(run, completion);
        } catch (ApiException e) {
            fail(run, e.code());
            throw e;
        }
    }

    /** 流式问答：sources 先于首个 token 发出，客户端可先核对依据再读正文。 */
    public Dto.ChatResponse stream(Actor actor, String kbId, Dto.ChatRequestDto request, String idempotencyKey,
                                   Consumer<String> onToken, SseSink sink) {
        Run run = begin(actor, kbId, request, idempotencyKey);
        sink.meta(run.requestId, run.conversationId, run.requestId);
        sink.sources(run.citations);
        if (run.replayed != null) {
            sink.done(run.replayed);
            return run.replayed;
        }
        try {
            ChatModelClient.Completion completion = run.usable.isEmpty()
                    ? null : model.stream(run.prompt.system(), run.prompt.user(), onToken);
            Dto.ChatResponse response = finish(run, completion);
            sink.done(response);
            return response;
        } catch (ApiException e) {
            fail(run, e.code());
            sink.error(e.code().name(), e.getMessage(), run.traceId);
            throw e;
        }
    }

    private Run begin(Actor actor, String kbId, Dto.ChatRequestDto request, String idempotencyKey) {
        limiter.acquire(actor);
        String question = request == null ? null : request.question();
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_CHARS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "问题长度需在 1~" + MAX_QUESTION_CHARS + " 字符内");
        }

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingId = store.findRequestIdByIdempotency(actor.tenantId(), actor.userId(), idempotencyKey);
            if (existingId != null) {
                ChatMapper.RequestRow row = store.findRequest(existingId);
                if (row != null) {
                    Run replay = new Run(actor, row.id(), row.conversationId(), TraceIds.current());
                    List<Dto.CitationView> views = CitationService.toViews(store.listByRequest(row.id()));
                    replay.citations = views;
                    replay.replayed = replayResponse(row, views);
                    return replay;
                }
            }
        }

        authorization.requireKbPermission(actor, kbId, "kb:read");
        LinkedHashSet<String> requested = new LinkedHashSet<>();
        requested.add(kbId);
        if (request.knowledgeBaseIds() != null) {
            requested.addAll(request.knowledgeBaseIds());
        }
        AuthorizationService.ScopedScope scoped =
                authorization.scope(actor, List.copyOf(requested), "kb:read");
        if (scoped.authorizedKbIds().isEmpty()) {
            audit.deny(actor, "chat.ask", "knowledge_base", kbId, ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "没有可用于问答的授权知识库");
        }

        long beginAuth = System.nanoTime();
        Map<String, KnowledgeBaseMapper.Row> kbById = new HashMap<>();
        List<String> engineKbIds = new ArrayList<>();
        for (String authorizedKbId : scoped.authorizedKbIds()) {
            KnowledgeBaseMapper.Row kb = knowledgeBases.findById(authorizedKbId);
            if (kb == null || !kb.tenantId().equals(actor.tenantId())) {
                continue;
            }
            kbById.put(kb.id(), kb);
            if (kb.engineKbId() != null && !kb.engineKbId().isBlank()) {
                engineKbIds.add(kb.engineKbId());
            }
        }
        long authorizationMs = (System.nanoTime() - beginAuth) / 1_000_000;

        String requestId = UUID.randomUUID().toString();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString() : request.conversationId();
        KnowledgeBaseMapper.Row anchor = kbById.get(kbId);
        Instant now = Instant.now();
        store.insertRequest(requestId, actor.tenantId(), anchor == null ? null : anchor.workspaceId(),
                actor.userId(), kbId, conversationId, anchor == null ? 0 : anchor.aclVersion(),
                idempotencyKey, question, TraceIds.current(), now);

        Run run = new Run(actor, requestId, conversationId, TraceIds.current());
        run.authorizationMs = authorizationMs;
        run.requestedKbCount = requested.size();
        run.authorizedKbCount = kbById.size();
        run.droppedScopes = scoped.droppedKbIds();

        if (engineKbIds.isEmpty()) {
            run.citations = List.of();
            run.prompt = new PromptBuilder.Built("", "", List.of(), 0);
            return run;
        }

        int topK = clamp(request.topK(), props.engine().topK(), 1, 50);
        long beginSearch = System.nanoTime();
        List<KnowledgeEnginePort.RetrievedChunk> candidates;
        try {
            candidates = engine.search(new KnowledgeEnginePort.SearchQuery(actor.tenantId(), engineKbIds,
                    question, topK * CANDIDATE_MULTIPLIER, CANDIDATE_MULTIPLIER));
        } catch (ApiException e) {
            fail(run, e.code());
            throw e;
        }
        run.retrievalMs = (System.nanoTime() - beginSearch) / 1_000_000;
        run.engineCandidates = candidates.size();

        int contextChunks = clamp(request.contextChunks(), props.engine().contextChunks(), 1, 12);
        run.usable = revalidate(actor, kbById, candidates, contextChunks, run);
        PromptBuilder.Built built = PromptBuilder.build(question, run.usable, props.ollama().maxPromptChars());
        run.usable = built.used();
        run.prompt = built;
        run.citations = persistCitations(run, actor);
        return run;
    }

    /** 逐条复核：引擎声明的范围与密级一律以 MySQL 当前行为准。 */
    private List<KnowledgeEnginePort.RetrievedChunk> revalidate(Actor actor,
                                                               Map<String, KnowledgeBaseMapper.Row> kbById,
                                                               List<KnowledgeEnginePort.RetrievedChunk> candidates,
                                                               int contextChunks, Run run) {
        List<KnowledgeEnginePort.RetrievedChunk> usable = new ArrayList<>();
        Map<String, DocumentMapper.Row> docCache = new HashMap<>();
        Map<String, Boolean> aclCache = new HashMap<>();
        for (KnowledgeEnginePort.RetrievedChunk chunk : candidates) {
            if (usable.size() >= contextChunks) {
                break;
            }
            KnowledgeBaseMapper.Row kb = kbById.get(chunk.kbId());
            if (kb == null || !actor.tenantId().equals(chunk.tenantId())) {
                run.droppedByAcl++;
                continue;
            }
            DocumentMapper.Row doc = docCache.computeIfAbsent(chunk.documentId(), documents::findById);
            if (doc == null || !doc.tenantId().equals(actor.tenantId()) || !doc.kbId().equals(chunk.kbId())
                    || !doc.workspaceId().equals(chunk.workspaceId())) {
                run.droppedByAcl++;
                continue;
            }
            if (!"READY".equals(doc.status()) || doc.version() != chunk.documentVersion()) {
                // 索引落后权威行或文档已下线：宁可当作无依据，也不用过期索引回答
                run.droppedByStale++;
                continue;
            }
            Classification required = highest(highest(Classification.parse(doc.classification()),
                    Classification.parse(chunk.classification())), kb.classification());
            if (!actor.clearance().atLeast(required)) {
                run.droppedByClearance++;
                continue;
            }
            if (aclSnapshot.stale(chunk.engineKbId(), kb.aclVersion())) {
                run.droppedByStale++;
                continue;
            }
            boolean readable = aclCache.computeIfAbsent(doc.id(),
                    id -> !doc.aclEnforced() || !documentAcls.grantsOf(id, actor.userId()).isEmpty());
            if (!readable) {
                run.droppedByAcl++;
                continue;
            }
            run.titles.put(doc.id(), doc.displayName());
            usable.add(chunk);
        }
        if (usable.isEmpty() && run.engineCandidates > 0) {
            audit.record(actor, "chat.evidence_rejected", "chat_request", run.requestId, "DENY",
                    ErrorCode.ACCESS_DENIED.name(),
                    Map.of("candidates", run.engineCandidates, "droppedByAcl", run.droppedByAcl,
                            "droppedByClearance", run.droppedByClearance, "droppedByStale", run.droppedByStale));
        }
        return usable;
    }

    private List<Dto.CitationView> persistCitations(Run run, Actor actor) {
        List<Dto.CitationView> views = new ArrayList<>();
        Instant now = Instant.now();
        for (KnowledgeEnginePort.RetrievedChunk chunk : run.usable) {
            String snippet = PromptBuilder.truncate(chunk.content(), SNIPPET_MAX_CHARS);
            String digest = Hashing.sha256Hex(snippet);
            String citationId = UUID.randomUUID().toString();
            String title = run.titles.getOrDefault(chunk.documentId(), chunk.engineDocId());
            store.insertCitation(citationId, actor.tenantId(), run.requestId, chunk.documentId(),
                    chunk.documentVersion(), chunk.kbId(), chunk.engineDocId(), chunk.engineChunkId(),
                    title, chunk.pageNo(), chunk.section(), chunk.charStart(), chunk.charEnd(),
                    snippet, digest, chunk.score(), now);
            views.add(new Dto.CitationView(citationId, chunk.documentId(), chunk.documentVersion(),
                    chunk.engineDocId(), chunk.engineChunkId(), title, chunk.pageNo(), chunk.section(),
                    chunk.charStart(), chunk.charEnd(), snippet, digest, chunk.score(), true));
        }
        return views;
    }

    private Dto.ChatResponse finish(Run run, ChatModelClient.Completion completion) {
        String status = completion == null ? "NO_EVIDENCE" : "ANSWERED";
        String answer = completion == null ? NO_EVIDENCE_ANSWER : completion.text();
        String errorCode = completion == null ? ErrorCode.CHAT_NO_EVIDENCE.name() : null;
        long totalMs = completion == null ? 0 : completion.totalMs();
        Long firstTokenMs = completion == null ? null : completion.firstTokenMs();
        store.completeRequest(run.requestId, status, answer, model.model(), run.usable.size(),
                run.engineCandidates, run.droppedByAcl, run.droppedByClearance, run.droppedByStale,
                run.authorizationMs, run.retrievalMs, firstTokenMs, totalMs,
                completion == null ? null : completion.promptTokens(),
                completion == null ? null : completion.completionTokens(), errorCode, Instant.now());
        audit.record(run.actor, "chat." + status, "chat_request", run.requestId, "ALLOW", null,
                Map.of("promptChunks", run.usable.size(), "citations", run.citations.size(),
                        "droppedByAcl", run.droppedByAcl, "droppedByClearance", run.droppedByClearance,
                        "droppedByStale", run.droppedByStale, "model", model.kind()));
        return response(run, status, answer, completion, firstTokenMs, totalMs);
    }

    private Dto.ChatResponse response(Run run, String status, String answer,
                                      ChatModelClient.Completion completion, Long firstTokenMs, long totalMs) {
        return new Dto.ChatResponse(run.requestId, run.conversationId, run.requestId, status, answer,
                run.citations,
                new Dto.Usage(completion == null ? null : completion.promptTokens(),
                        completion == null ? null : completion.completionTokens()),
                new Dto.Latency(run.authorizationMs, run.retrievalMs, firstTokenMs, totalMs),
                run.aclStats(), run.traceId);
    }

    private Dto.ChatResponse replayResponse(ChatMapper.RequestRow row, List<Dto.CitationView> views) {
        return new Dto.ChatResponse(row.id(), row.conversationId(), row.id(), row.status(),
                row.answer() == null ? "" : row.answer(), views,
                new Dto.Usage(row.promptTokens(), row.completionTokens()),
                new Dto.Latency(row.authorizationMs(), row.retrievalMs(), row.firstTokenMs(), row.totalMs()),
                new Dto.AclStats(0, 0, row.engineCandidates(), row.droppedByAcl(), row.droppedByClearance(),
                        row.droppedByStale(), row.promptChunks(), List.of()),
                row.traceId());
    }

    private void fail(Run run, ErrorCode code) {
        try {
            store.completeRequest(run.requestId, "ERROR", null, model.model(), 0, run.engineCandidates,
                    run.droppedByAcl, run.droppedByClearance, run.droppedByStale, run.authorizationMs,
                    run.retrievalMs, null, 0, null, null, code.name(), Instant.now());
        } catch (RuntimeException e) {
            log.warn("chat_request_close_failed err={}", e.getClass().getSimpleName());
        }
        audit.deny(run.actor, "chat.ask", "chat_request", run.requestId, code.name());
    }

    private static Classification highest(Classification a, Classification b) {
        return a.rank() >= b.rank() ? a : b;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int v = value == null ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    /** 一次问答的执行态：复核计数在逐条复核中累加，因此用可变内部类而不是 record。 */
    private static final class Run {
        private final Actor actor;
        private final String requestId;
        private final String conversationId;
        private final String traceId;
        private final Map<String, String> titles = new HashMap<>();
        private Dto.ChatResponse replayed;
        private long authorizationMs;
        private long retrievalMs;
        private int requestedKbCount;
        private int authorizedKbCount;
        private int engineCandidates;
        private int droppedByAcl;
        private int droppedByClearance;
        private int droppedByStale;
        private List<String> droppedScopes = List.of();
        private List<KnowledgeEnginePort.RetrievedChunk> usable = List.of();
        private PromptBuilder.Built prompt;
        private List<Dto.CitationView> citations = List.of();

        private Run(Actor actor, String requestId, String conversationId, String traceId) {
            this.actor = actor;
            this.requestId = requestId;
            this.conversationId = conversationId;
            this.traceId = traceId;
        }

        private Dto.AclStats aclStats() {
            return new Dto.AclStats(requestedKbCount, authorizedKbCount, engineCandidates, droppedByAcl,
                    droppedByClearance, droppedByStale, usable.size(), droppedScopes);
        }
    }
}
