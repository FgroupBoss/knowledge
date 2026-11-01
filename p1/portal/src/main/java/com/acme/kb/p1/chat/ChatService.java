package com.acme.kb.p1.chat;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.authz.AuthorizationService;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.common.TraceIds;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.engine.ChatModelClient;
import com.acme.kb.p1.engine.EngineAclSnapshot;
import com.acme.kb.p1.engine.KnowledgeEnginePort;
import com.acme.kb.p1.repo.ChatMapper;
import com.acme.kb.p1.repo.DocumentAclMapper;
import com.acme.kb.p1.repo.DocumentMapper;
import com.acme.kb.p1.repo.KnowledgeBaseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索问答：授权范围在 Java 侧确定，引擎召回后逐条回 MySQL 复核，未通过复核的片段绝不进入 Prompt。
 * 引擎返回值一律按不可信输入处理；越权证据由 aclStats 计数留痕，正文不外泄。
 *
 * <p>P1 状态口径：只有 ANSWERED 落引用；NO_EVIDENCE（无候选/无过阈值）与 REFUSED（模型输出
 * NO_ANSWER 哨兵）一律 citations=[]。落库、引用、审计由 {@link ChatWriteTx} 同事务提交。</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final int MAX_QUESTION_CHARS = 4000;
    private static final int SNIPPET_MAX_CHARS = 2000;
    private static final int CANDIDATE_MULTIPLIER = 3;
    private static final String PROMPT_VERSION = "p1-chat-v1";
    private static final String REFUSAL_SENTINEL = "NO_ANSWER";
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d{1,3})]");
    private static final String NO_EVIDENCE_ANSWER = "当前授权知识库中没有检索到足够依据，我不会用猜测补全答案。"
            + "可以补充相关文档，或换一个更具体的问法。";

    private final AuthorizationService authorization;
    private final KnowledgeBaseMapper knowledgeBases;
    private final DocumentMapper documents;
    private final DocumentAclMapper documentAcls;
    private final ChatMapper store;
    private final ChatWriteTx writeTx;
    private final KnowledgeEnginePort engine;
    private final ChatModelClient model;
    private final EngineAclSnapshot aclSnapshot;
    private final ChatRateLimiter limiter;
    private final AuditService audit;
    private final KbProperties props;

    public ChatService(AuthorizationService authorization, KnowledgeBaseMapper knowledgeBases,
                       DocumentMapper documents, DocumentAclMapper documentAcls, ChatMapper store,
                       ChatWriteTx writeTx, KnowledgeEnginePort engine, ChatModelClient model,
                       EngineAclSnapshot aclSnapshot, ChatRateLimiter limiter, AuditService audit,
                       KbProperties props) {
        this.authorization = authorization;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.documentAcls = documentAcls;
        this.store = store;
        this.writeTx = writeTx;
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
            fail(run, e);
            throw e;
        }
    }

    /**
     * 流式问答。sources 只会在回答状态确定后发出：REFUSED/NO_EVIDENCE 必须携带空引用，
     * 不能先把候选片段当作依据推给前端再改口。
     */
    public Dto.ChatResponse stream(Actor actor, String kbId, Dto.ChatRequestDto request, String idempotencyKey,
                                   Consumer<String> onToken, SseSink sink) {
        Run run = begin(actor, kbId, request, idempotencyKey);
        sink.meta(run.requestId, run.conversationId, run.messageId);
        if (run.replayed != null) {
            sink.sources(run.replayed.citations());
            sink.done(run.replayed);
            return run.replayed;
        }
        sink.onDisconnect(() -> cancelQuietly(actor, run.requestId));
        try {
            ChatModelClient.Completion completion = run.usable.isEmpty()
                    ? null : model.stream(run.prompt.system(), run.prompt.user(), onToken);
            Dto.ChatResponse response = finish(run, completion);
            sink.sources(response.citations());
            sink.done(response);
            return response;
        } catch (ApiException e) {
            fail(run, e);
            sink.error(e.code().name(), e.getMessage(), run.traceId);
            throw e;
        }
    }

    /** 显式取消；非所有者或已结束按错误返回。 */
    public void cancel(Actor actor, String requestId) {
        writeTx.cancel(actor, requestId);
    }

    private void cancelQuietly(Actor actor, String requestId) {
        try {
            writeTx.cancel(actor, requestId);
        } catch (RuntimeException e) {
            log.debug("chat_cancel_on_disconnect_skipped request={} err={}", requestId,
                    e.getClass().getSimpleName());
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
                    Run replay = new Run(actor, row.id(), row.messageId(), row.conversationId(),
                            TraceIds.current());
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
        long aclVersion = anchor == null ? 0 : anchor.aclVersion();
        String traceId = TraceIds.current();
        String messageId = UUID.randomUUID().toString();

        writeTx.start(new ChatWriteTx.Start(requestId, UUID.randomUUID().toString(), messageId,
                actor.tenantId(), anchor == null ? null : anchor.workspaceId(), actor.userId(), kbId,
                conversationId, aclVersion, idempotencyKey, question, traceId, question));

        Run run = new Run(actor, requestId, messageId, conversationId, traceId);
        run.authorizationMs = authorizationMs;
        run.requestedKbCount = requested.size();
        run.authorizedKbCount = kbById.size();
        run.droppedScopes = scoped.droppedKbIds();
        run.kbById = kbById;
        run.citationConfigHash = configHash(engineKbIds, request);

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
            fail(run, e);
            throw e;
        }
        run.retrievalMs = (System.nanoTime() - beginSearch) / 1_000_000;
        run.engineCandidates = candidates.size();

        // 阈值来自 calibration.json（运行时配置），低于阈值的候选不进入复核与 Prompt
        double threshold = props.engine().scoreThreshold();
        List<KnowledgeEnginePort.RetrievedChunk> aboveThreshold = new ArrayList<>();
        for (KnowledgeEnginePort.RetrievedChunk chunk : candidates) {
            if (chunk.score() >= threshold) {
                aboveThreshold.add(chunk);
            }
        }
        int contextChunks = clamp(request.contextChunks(), props.engine().contextChunks(), 1, 12);
        run.usable = revalidate(actor, kbById, aboveThreshold, contextChunks, run);
        PromptBuilder.Built built = PromptBuilder.build(question, run.usable, props.ollama().maxPromptChars());
        run.usable = built.used();
        run.prompt = built;
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
        return usable;
    }

    private Dto.ChatResponse finish(Run run, ChatModelClient.Completion completion) {
        String status;
        String answer;
        String refusalReason = null;
        String errorCode = null;
        List<ChatWriteTx.CitationDraft> drafts = List.of();

        if (completion == null || run.usable.isEmpty()) {
            status = "NO_EVIDENCE";
            answer = NO_EVIDENCE_ANSWER;
            refusalReason = "NO_EVIDENCE";
            errorCode = ErrorCode.CHAT_NO_EVIDENCE.name();
        } else if (isRefusal(completion.text())) {
            status = "REFUSED";
            answer = NO_EVIDENCE_ANSWER;
            refusalReason = "MODEL_NO_ANSWER";
            errorCode = ErrorCode.CHAT_NO_EVIDENCE.name();
        } else {
            status = "ANSWERED";
            answer = completion.text();
            drafts = citationDrafts(run, answer);
        }

        long totalMs = completion == null ? 0 : completion.totalMs();
        Long firstTokenMs = completion == null ? null : completion.firstTokenMs();
        ChatWriteTx.Completion c = new ChatWriteTx.Completion(run.requestId, run.messageId,
                run.actor.tenantId(), run.conversationId, status, refusalReason, answer,
                completion == null ? null : model.model(), modelDigest(), PROMPT_VERSION,
                run.citationConfigHash, run.usable.size(), run.engineCandidates, run.droppedByAcl,
                run.droppedByClearance, run.droppedByStale, run.droppedByUnsupportedCitation,
                run.authorizationMs, run.retrievalMs, firstTokenMs, totalMs,
                completion == null ? null : completion.promptTokens(),
                completion == null ? null : completion.completionTokens(), errorCode);
        run.citations = writeTx.complete(run.actor, c, drafts);
        return response(run, status, answer, completion, firstTokenMs, totalMs);
    }

    /**
     * 引用必须映射到本次进入 Prompt 的 chunk。模型给出 [n] 时只保留被引用的编号；
     * 越界编号计入 droppedByUnsupportedCitation。模型没给编号时保留全部已复核片段。
     */
    private List<ChatWriteTx.CitationDraft> citationDrafts(Run run, String answer) {
        int usable = run.usable.size();
        Set<Integer> cited = new TreeSet<>();
        Matcher matcher = CITATION_MARKER.matcher(answer == null ? "" : answer);
        while (matcher.find()) {
            int number;
            try {
                number = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (number >= 1 && number <= usable) {
                cited.add(number);
            } else {
                run.droppedByUnsupportedCitation++;
            }
        }
        List<ChatWriteTx.CitationDraft> drafts = new ArrayList<>();
        for (int i = 0; i < usable; i++) {
            if (!cited.isEmpty() && !cited.contains(i + 1)) {
                continue;
            }
            KnowledgeEnginePort.RetrievedChunk chunk = run.usable.get(i);
            String snippet = PromptBuilder.truncate(chunk.content(), SNIPPET_MAX_CHARS);
            String title = run.titles.getOrDefault(chunk.documentId(), chunk.engineDocId());
            long aclVersion = run.kbById.containsKey(chunk.kbId())
                    ? run.kbById.get(chunk.kbId()).aclVersion() : 0;
            drafts.add(new ChatWriteTx.CitationDraft(chunk.documentId(), chunk.documentVersion(),
                    chunk.kbId(), chunk.engineDocId(), chunk.engineChunkId(), title, chunk.pageNo(),
                    chunk.section(), chunk.charStart(), chunk.charEnd(), snippet,
                    Hashing.sha256Hex(snippet), chunk.score(), aclVersion));
        }
        return drafts;
    }

    /** 拒答判定：空输出或前 64 字符以 NO_ANSWER 开头。包可见以便单测覆盖。 */
    static boolean isRefusal(String text) {
        if (text == null) {
            return true;
        }
        String flat = text.strip();
        if (flat.isEmpty()) {
            return true;
        }
        String head = flat.length() > 64 ? flat.substring(0, 64) : flat;
        return head.toUpperCase(java.util.Locale.ROOT).startsWith(REFUSAL_SENTINEL);
    }

    private Dto.ChatResponse response(Run run, String status, String answer,
                                      ChatModelClient.Completion completion, Long firstTokenMs, long totalMs) {
        return new Dto.ChatResponse(run.requestId, run.conversationId, run.messageId, status, answer,
                run.citations,
                new Dto.Usage(completion == null ? null : completion.promptTokens(),
                        completion == null ? null : completion.completionTokens()),
                new Dto.Latency(run.authorizationMs, run.retrievalMs, firstTokenMs, totalMs),
                run.aclStats(), run.traceId);
    }

    private Dto.ChatResponse replayResponse(ChatMapper.RequestRow row, List<Dto.CitationView> views) {
        return new Dto.ChatResponse(row.id(), row.conversationId(),
                row.messageId() == null ? row.id() : row.messageId(), row.status(),
                row.answer() == null ? "" : row.answer(), views,
                new Dto.Usage(row.promptTokens(), row.completionTokens()),
                new Dto.Latency(row.authorizationMs(), row.retrievalMs(), row.firstTokenMs(), row.totalMs()),
                new Dto.AclStats(0, 0, row.engineCandidates(), row.droppedByAcl(), row.droppedByClearance(),
                        row.droppedByStale(), row.droppedByUnsupportedCitation(), row.promptChunks(), List.of()),
                row.traceId());
    }

    private void fail(Run run, ApiException error) {
        try {
            writeTx.fail(run.actor, new ChatWriteTx.Completion(run.requestId, run.messageId,
                    run.actor.tenantId(), run.conversationId, "ERROR", null, null, model.model(), null,
                    PROMPT_VERSION, run.citationConfigHash, 0, run.engineCandidates, run.droppedByAcl,
                    run.droppedByClearance, run.droppedByStale, run.droppedByUnsupportedCitation,
                    run.authorizationMs, run.retrievalMs, null, 0, null, null, error.code().name()));
        } catch (RuntimeException e) {
            log.warn("chat_request_close_failed err={}", e.getClass().getSimpleName());
        }
    }

    private String modelDigest() {
        try {
            return Hashing.sha256Hex(model.kind() + "|" + model.model()).substring(0, 32);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String configHash(List<String> engineKbIds, Dto.ChatRequestDto request) {
        StringBuilder sb = new StringBuilder();
        sb.append("topK=").append(request.topK())
                .append("|context=").append(request.contextChunks())
                .append("|threshold=").append(props.engine().scoreThreshold())
                .append("|prompt=").append(PROMPT_VERSION)
                .append("|kbs=").append(String.join(",", engineKbIds));
        return Hashing.sha256Hex(sb.toString());
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
        private final String messageId;
        private final String conversationId;
        private final String traceId;
        private final Map<String, String> titles = new HashMap<>();
        private Map<String, KnowledgeBaseMapper.Row> kbById = Map.of();
        private String citationConfigHash;
        private Dto.ChatResponse replayed;
        private long authorizationMs;
        private long retrievalMs;
        private int requestedKbCount;
        private int authorizedKbCount;
        private int engineCandidates;
        private int droppedByAcl;
        private int droppedByClearance;
        private int droppedByStale;
        private int droppedByUnsupportedCitation;
        private List<String> droppedScopes = List.of();
        private List<KnowledgeEnginePort.RetrievedChunk> usable = List.of();
        private PromptBuilder.Built prompt;
        private List<Dto.CitationView> citations = List.of();

        private Run(Actor actor, String requestId, String messageId, String conversationId, String traceId) {
            this.actor = actor;
            this.requestId = requestId;
            this.messageId = messageId;
            this.conversationId = conversationId;
            this.traceId = traceId;
        }

        private Dto.AclStats aclStats() {
            return new Dto.AclStats(requestedKbCount, authorizedKbCount, engineCandidates, droppedByAcl,
                    droppedByClearance, droppedByStale, droppedByUnsupportedCitation, usable.size(),
                    droppedScopes);
        }
    }
}
