package com.acme.kb.p1.wiki;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.authz.AuthorizationService;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.DocumentMapper;
import com.acme.kb.p1.repo.KnowledgeBaseMapper;
import com.acme.kb.p1.repo.WikiQueryMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 来源引用守卫（D-10）：**保存/提交时校验可见性，读取时回查可得性**。
 *
 * 断链不阻断读取，只把来源标成不可用并计入新鲜度；提交与发布则是硬门槛——
 * 没有来源的 Wiki 页面等于把"未经证据的结论"放进问答引用，属越权可见性风险。
 */
@Component
public class WikiSourceGuard {

    public static final String TYPE_DOCUMENT = "DOCUMENT";
    /** 页面间引用属 P2-W3：本轮不接受，也不假装支持（避免留下无法产生审计的分支）。 */
    public static final String TYPE_WIKI_PAGE = "WIKI_PAGE";

    private final WikiQueryMapper wiki;
    private final DocumentMapper documents;
    private final KnowledgeBaseMapper knowledgeBases;
    private final AuthorizationService authorization;

    public WikiSourceGuard(WikiQueryMapper wiki, DocumentMapper documents,
                           KnowledgeBaseMapper knowledgeBases, AuthorizationService authorization) {
        this.wiki = wiki;
        this.documents = documents;
        this.knowledgeBases = knowledgeBases;
        this.authorization = authorization;
    }

    /** 挂接来源时就要求 actor 能读到该文档：否则任何人可用别人的文档当"证据"骗过审核。 */
    public DocumentMapper.Row requireAttachable(Actor actor, String sourceType, String sourceId,
                                                Integer sourceVersion) {
        if (TYPE_WIKI_PAGE.equals(sourceType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "页面间引用尚未开放（P2-W3）");
        }
        if (!TYPE_DOCUMENT.equals(sourceType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知来源类型");
        }
        DocumentMapper.Row doc = documents.findById(sourceId);
        if (doc == null || !doc.tenantId().equals(actor.tenantId())) {
            throw new ApiException(ErrorCode.WIKI_SOURCE_UNAVAILABLE, "来源文档不存在或已不可用");
        }
        authorization.requireDocumentRead(actor, sourceId);
        int wanted = sourceVersion == null ? doc.version() : sourceVersion;
        if (wanted != doc.version()) {
            throw new ApiException(ErrorCode.WIKI_SOURCE_UNAVAILABLE, "来源文档版本不存在");
        }
        if (!"READY".equals(doc.status())) {
            throw new ApiException(ErrorCode.WIKI_SOURCE_UNAVAILABLE, "来源文档尚未入库完成");
        }
        return doc;
    }

    /** 提交与发布的前置校验（契约 §2 E1/E3 失败码）：至少一条来源，且每条当前可得。 */
    public void requireSubmittable(Actor actor, String revisionId) {
        List<WikiQueryMapper.SourceRow> sources = wiki.listSources(revisionId);
        if (sources.isEmpty()) {
            throw new ApiException(ErrorCode.WIKI_SOURCE_UNAVAILABLE,
                    "页面至少需要一条来源才能提交审核");
        }
        for (WikiQueryMapper.SourceRow source : sources) {
            if (!available(actor, source)) {
                throw new ApiException(ErrorCode.WIKI_SOURCE_UNAVAILABLE,
                        "来源文档已不可用或版本变更，请重新关联");
            }
        }
    }

    /** 读取侧回查：不抛异常，只给布尔，视图据此标 STALE。 */
    public boolean available(Actor actor, WikiQueryMapper.SourceRow source) {
        if (!TYPE_DOCUMENT.equals(source.sourceType())) {
            return false;
        }
        DocumentMapper.Row doc = documents.findById(source.sourceId());
        if (doc == null || !doc.tenantId().equals(actor.tenantId()) || !"READY".equals(doc.status())) {
            return false;
        }
        return source.sourceVersion() == null || source.sourceVersion() == doc.version();
    }

    /** 关联当时记录 KB 的 acl_version，读取时能判断"授权边界是否已变"（ADR-002）。 */
    public long aclVersionAt(Actor actor, String kbId) {
        Long version = knowledgeBases.aclVersion(kbId, actor.tenantId());
        return version == null ? 0L : version;
    }
}
