package com.acme.kb.p1.wiki;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.authz.AclDecision;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.WikiQueryMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 治理能力计算（D-05）：**不动 `WorkspaceRole` 枚举**，审核权由 `wiki_review_assignment` 行决定，
 * 其余动作沿用 KB 权限位。这样 P1 的授权矩阵证据（`05-verify-authz.py`）继续有效。
 *
 * 输出是服务端算好的 capability 列表，前端只按它置灰按钮，不作为授权依据（授权在服务端逐次判定）。
 */
@Component
public class WikiCapabilities {

    public static final String READ = "wiki:read";
    public static final String READ_DRAFT = "wiki:read-draft";
    public static final String WRITE = "wiki:write";
    public static final String SUBMIT = "wiki:submit";
    public static final String REVIEW = "wiki:review";
    public static final String PUBLISH = "wiki:publish";
    public static final String RETRACT = "wiki:retract";
    public static final String GOVERN = "wiki:govern";

    /** 页面负责人自己可以撤稿与治理，但不等于可以审核（自批另由 E3 前置校验挡住）。 */
    private static final Set<String> PRIVILEGED = Set.of("OWNER", "ADMIN");

    private final WikiQueryMapper queries;

    public WikiCapabilities(WikiQueryMapper queries) {
        this.queries = queries;
    }

    public record Grants(boolean read, boolean readDraft, boolean write, boolean submit, boolean review,
                         boolean retract, boolean govern) {

        public List<String> names() {
            Set<String> out = new LinkedHashSet<>();
            if (read) {
                out.add(READ);
            }
            if (readDraft) {
                out.add(READ_DRAFT);
            }
            if (write) {
                out.add(WRITE);
            }
            if (submit) {
                out.add(SUBMIT);
            }
            if (review) {
                out.add(REVIEW);
            }
            if (retract) {
                out.add(RETRACT);
            }
            if (govern) {
                out.add(GOVERN);
            }
            return List.copyOf(out);
        }
    }

    public Grants compute(Actor actor, AclDecision kb, WikiQueryMapper.PageRow page,
                          WikiQueryMapper.RevisionRow revision) {
        return compute(actor, kb, page, revision, assigned(actor, page));
    }

    /** 列表页把指派查询提到循环外，避免逐行查 `wiki_review_assignment`。 */
    public Grants compute(Actor actor, AclDecision kb, WikiQueryMapper.PageRow page,
                          WikiQueryMapper.RevisionRow revision, boolean assigned) {
        boolean privileged = kb.role() != null && PRIVILEGED.contains(kb.role());
        boolean read = kb.allows("kb:read");
        boolean write = kb.allows("kb:write");
        boolean review = privileged || assigned;
        boolean owner = actor.userId().equals(page.ownerUserId());
        boolean author = revision != null && actor.userId().equals(revision.authorUserId());
        // 草稿可见性就是"作者本人、被指派的审核人、空间 OWNER/ADMIN"三个人的集合（契约 openapi getWikiPage 描述）。
        // 不叠加 write：VIEWER 角色被指派为审核人时也要能读到待审正文，否则审核队列里有读不了的单子。
        boolean readDraft = author || review || owner;
        boolean retract = privileged || owner || review;
        return new Grants(read, readDraft, write, write, review, retract, privileged || owner);
    }

    /** 指派表里有没有这个人的审核行（D-05：能力来自行，不来自新角色）。 */
    public boolean assigned(Actor actor, WikiQueryMapper.PageRow page) {
        return queries.reviewAssigned(actor.tenantId(), actor.userId(), page.workspaceId(), page.kbId()) > 0;
    }

    /** 审核能力缺失走专用码：客户端动作是"走指派流程"，不是"停止"（error-codes.md）。 */
    public void requireReview(Grants grants) {
        if (!grants.review()) {
            throw new ApiException(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED,
                    "当前身份未被指派为审核人，不能审核该修订");
        }
    }

    public void requireSubmit(Grants grants) {
        if (!grants.submit()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许提交审核");
        }
    }

    public void requireWrite(Grants grants) {
        if (!grants.write()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前角色不允许编辑页面");
        }
    }

    public void requireRetract(Grants grants) {
        if (!grants.retract()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前身份不允许撤稿或归档");
        }
    }

    public void requireGovern(Grants grants) {
        if (!grants.govern()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前身份不允许修改治理位");
        }
    }

    public void requireRead(Grants grants) {
        if (!grants.read()) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前身份不允许读取该页面");
        }
    }
}
