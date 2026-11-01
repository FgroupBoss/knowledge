package com.acme.kb.p1.chat;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.ChatMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 问答的落库边界。P1 要求“回答状态 + 引用 + 审计”在同一事务里提交：
 * 不可能出现“答案已返回但引用没落库”，也不会出现“引用落库但审计缺失”。
 *
 * 状态口径（#3）：
 * - ANSWERED 才写 citation 行；
 * - NO_EVIDENCE / REFUSED 一律零引用，拒绝原因写入 refusal_reason；
 * - ERROR / CANCELLED 不写引用。
 */
@Service
public class ChatWriteTx {

    private final ChatMapper store;
    private final AuditService audit;

    public ChatWriteTx(ChatMapper store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public record Start(String requestId, String userMessageId, String messageId, String tenantId,
                        String workspaceId, String actorId, String kbId, String conversationId,
                        long aclVersion, String idempotencyKey, String question, String traceId,
                        String conversationTitle) {
    }

    public record CitationDraft(String documentId, int documentVersion, String kbId, String engineDocId,
                                String engineChunkId, String title, Integer pageNo, String section,
                                Integer charStart, Integer charEnd, String snippet, String snippetDigest,
                                double score, long aclVersion) {
    }

    public record Completion(String requestId, String messageId, String tenantId, String conversationId,
                             String status, String refusalReason, String answer, String engineModel,
                             String modelDigest, String promptVersion, String retrievalConfigHash,
                             int promptChunks, int engineCandidates, int droppedByAcl,
                             int droppedByClearance, int droppedByStale,
                             int droppedByUnsupportedCitation, long authorizationMs, long retrievalMs,
                             Long firstTokenMs, long totalMs, Integer promptTokens,
                             Integer completionTokens, String errorCode) {
    }

    /** 会话 + 用户消息 + 请求行同一事务：刷新后不会出现“有请求无消息”。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void start(Start s) {
        Instant now = Instant.now();
        String title = s.conversationTitle() == null || s.conversationTitle().isBlank()
                ? "新会话" : truncate(s.conversationTitle(), 200);
        store.insertConversation(s.conversationId(), s.tenantId(), s.workspaceId(), s.actorId(), title, now);
        store.insertMessage(s.userMessageId(), s.tenantId(), s.conversationId(), s.requestId(), "USER",
                s.question(), "DONE", now);
        store.insertRequest(s.requestId(), s.messageId(), s.tenantId(), s.workspaceId(), s.actorId(),
                s.kbId(), s.conversationId(), s.aclVersion(), s.idempotencyKey(), s.question(),
                s.traceId(), now);
        store.touchConversation(s.conversationId(), now);
    }

    /**
     * 完成一次问答：引用、请求终态、助手消息、审计四项同事务提交。
     * citations 为空时（NO_EVIDENCE/REFUSED）不写任何 citation 行。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public List<Dto.CitationView> complete(Actor actor, Completion c, List<CitationDraft> drafts) {
        Instant now = Instant.now();
        List<Dto.CitationView> views = new ArrayList<>();
        List<CitationDraft> persisted = "ANSWERED".equals(c.status()) ? drafts : List.of();
        int no = 0;
        for (CitationDraft d : persisted) {
            no++;
            String citationId = UUID.randomUUID().toString();
            String contentDigest = d.snippetDigest();
            String locator = "doc/" + d.documentId() + "/v" + d.documentVersion()
                    + "/chars/" + nz(d.charStart()) + "-" + nz(d.charEnd());
            store.insertCitation(citationId, c.messageId(), no, actor.tenantId(), c.requestId(),
                    d.documentId(), d.documentVersion(), d.kbId(), d.engineDocId(), d.engineChunkId(),
                    d.title(), d.pageNo(), d.section(), d.charStart(), d.charEnd(), d.snippet(),
                    d.snippetDigest(), contentDigest, locator, d.score(), d.aclVersion(), now);
            views.add(new Dto.CitationView(citationId, d.documentId(), d.documentVersion(),
                    d.engineDocId(), d.engineChunkId(), d.title(), d.pageNo(), d.section(),
                    d.charStart(), d.charEnd(), d.snippet(), d.snippetDigest(), d.score(), true));
        }
        store.completeRequest(c.requestId(), c.status(), c.refusalReason(), c.answer(), c.engineModel(),
                c.modelDigest(), c.promptVersion(), c.retrievalConfigHash(), c.promptChunks(),
                c.engineCandidates(), c.droppedByAcl(), c.droppedByClearance(), c.droppedByStale(),
                c.droppedByUnsupportedCitation(), c.authorizationMs(), c.retrievalMs(), c.firstTokenMs(),
                c.totalMs(), c.promptTokens(), c.completionTokens(), c.errorCode(), now);
        store.insertMessage(c.messageId(), actor.tenantId(), c.conversationId(), c.requestId(), "ASSISTANT",
                c.answer(), c.status(), now);
        store.touchConversation(c.conversationId(), now);

        boolean rejectedEvidence = !"ANSWERED".equals(c.status()) && c.engineCandidates() > 0
                && c.promptChunks() == 0 && c.droppedByAcl() > 0;
        String action = switch (c.status()) {
            case "ANSWERED" -> "chat.answer";
            case "REFUSED" -> "chat.refused";
            case "NO_EVIDENCE" -> rejectedEvidence ? "chat.evidence_rejected" : "chat.no_evidence";
            default -> "chat." + c.status().toLowerCase();
        };
        String result = rejectedEvidence ? "DENY" : "ALLOW";
        audit.recordInTx(actor, action, "chat_request", c.requestId(), result,
                rejectedEvidence ? "ACCESS_DENIED" : null,
                Map.of("citations", views.size(), "promptChunks", c.promptChunks(),
                        "engineCandidates", c.engineCandidates(), "droppedByAcl", c.droppedByAcl(),
                        "droppedByClearance", c.droppedByClearance(),
                        "droppedByStaleVersion", c.droppedByStale(),
                        "droppedByUnsupportedCitation", c.droppedByUnsupportedCitation(),
                        "model", c.engineModel() == null ? "" : c.engineModel()));
        return views;
    }

    /** 取消：仅请求所有者可取消 RUNNING 状态的生成。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void cancel(Actor actor, String requestId) {
        ChatMapper.RequestRow row = store.findRequest(requestId);
        if (row == null || !row.tenantId().equals(actor.tenantId())
                || !row.actorId().equals(actor.userId())) {
            throw new ApiException(ErrorCode.CONVERSATION_NOT_FOUND, "问答请求不存在");
        }
        if (store.cancelRequest(requestId, Instant.now()) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "请求已结束，无法取消");
        }
        audit.recordInTx(actor, "chat.cancel", "chat_request", requestId, "ALLOW", null, Map.of());
    }

    /** 失败收尾：请求置 ERROR + 审计同事务；失败本身不吞掉原始错误。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void fail(Actor actor, Completion c) {
        Instant now = Instant.now();
        store.completeRequest(c.requestId(), "ERROR", c.errorCode(), null, c.engineModel(), null, null,
                null, 0, c.engineCandidates(), c.droppedByAcl(), c.droppedByClearance(),
                c.droppedByStale(), c.droppedByUnsupportedCitation(), c.authorizationMs(), c.retrievalMs(),
                null, 0, null, null, c.errorCode(), now);
        store.insertMessage(c.messageId(), actor.tenantId(), c.conversationId(), c.requestId(), "ASSISTANT",
                null, "ERROR", now);
        audit.recordInTx(actor, "chat.failed", "chat_request", c.requestId(), "DENY", c.errorCode(),
                Map.of("engineCandidates", c.engineCandidates()));
    }

    private static String truncate(String value, int max) {
        String flat = value.strip().replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private static String nz(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }
}
