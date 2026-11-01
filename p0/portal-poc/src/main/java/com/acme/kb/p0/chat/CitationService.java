package com.acme.kb.p0.chat;

import com.acme.kb.p0.api.Dto;
import com.acme.kb.p0.audit.AuditService;
import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.authz.AuthorizationService;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.engine.parsing.DocumentParser;
import com.acme.kb.p0.engine.parsing.ParsedDocument;
import com.acme.kb.p0.repo.ChatMapper;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.storage.ObjectStorageService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用预览：每次请求重新走完整授权链，正文由服务端代理读取，
 * 永不返回对象存储地址或预签名链接（验收项"引用可回溯且不泄露越权原文"）。
 */
@Service
public class CitationService {

    private static final int RESLICE_MAX_BYTES = 8 * 1024 * 1024;

    private final AuthorizationService authorization;
    private final ChatMapper citations;
    private final DocumentMapper documents;
    private final ObjectStorageService storage;
    private final DocumentParser parser;
    private final AuditService audit;

    public CitationService(AuthorizationService authorization, ChatMapper citations, DocumentMapper documents,
                           ObjectStorageService storage, DocumentParser parser, AuditService audit) {
        this.authorization = authorization;
        this.citations = citations;
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.audit = audit;
    }

    public String preview(Actor actor, String citationId) {
        ChatMapper.CitationRow row = citations.findCitation(citationId);
        if (row == null) {
            throw ApiException.notFound("引用");
        }
        if (!row.tenantId().equals(actor.tenantId())) {
            audit.deny(actor, "citation.preview", "citation", citationId,
                    ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("引用");
        }
        DocumentMapper.Row doc = authorization.requireDocumentRead(actor, row.documentId());
        if (doc.version() != row.documentVersion()) {
            // 索引版本已被新版本替换：旧引用不再可回溯，按不存在处理
            audit.deny(actor, "citation.preview", "citation", citationId,
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw ApiException.notFound("引用");
        }
        String located = reslice(doc, row);
        String body = located == null ? row.snippet() : located;
        audit.allow(actor, "citation.preview", "citation", citationId);
        return PromptBuilder.truncate(body, 2000);
    }

    /** 优先按字符偏移从权威原件重新切片，确保引用文本来自原件而非索引副本。 */
    private String reslice(DocumentMapper.Row doc, ChatMapper.CitationRow row) {
        if (row.charStart() == null || row.charEnd() == null || row.charEnd() <= row.charStart()
                || doc.sizeBytes() > RESLICE_MAX_BYTES) {
            return null;
        }
        try {
            byte[] original = storage.readAll(doc.objectKey());
            ParsedDocument parsed = parser.parse(original, doc.displayName(), doc.mimeType());
            StringBuilder full = new StringBuilder();
            for (ParsedDocument.Block block : parsed.blocks()) {
                if (full.length() > 0) {
                    full.append('\n');
                }
                full.append(block.text());
            }
            int from = Math.min(row.charStart(), full.length());
            int to = Math.min(row.charEnd(), full.length());
            return to <= from ? null : full.substring(from, to);
        } catch (RuntimeException e) {
            // 解析不出原文时退回入库快照，但绝不因此放宽授权
            return null;
        }
    }

    public List<Dto.CitationView> ofRequest(String requestId) {
        return toViews(citations.listByRequest(requestId));
    }

    public static List<Dto.CitationView> toViews(List<ChatMapper.CitationRow> rows) {
        List<Dto.CitationView> views = new ArrayList<>(rows.size());
        for (ChatMapper.CitationRow row : rows) {
            views.add(new Dto.CitationView(row.id(), row.documentId(), row.documentVersion(),
                    row.engineDocId(), row.engineChunkId(), row.title(), row.pageNo(), row.section(),
                    row.charStart(), row.charEnd(), row.snippet(), row.snippetDigest(), row.score(),
                    row.aclVerified()));
        }
        return views;
    }
}
