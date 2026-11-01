package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Map;

/**
 * Wiki 写入侧。每条状态迁移都带前置状态或乐观锁条件（`WHERE status = 前置态` /
 * `WHERE current_revision_no = :ifMatch` / `WHERE acl_version = :ifMatch`），
 * 影响 0 行即由服务层重新读状态并转成 409，绝不静默覆盖（AGENTS.md §8、契约 §5.1 规则 1）。
 */
@Mapper
public interface WikiCommandMapper {

    @Insert("""
            INSERT INTO wiki_page (id, tenant_id, workspace_id, kb_id, slug, title, status, freshness,
                                   owner_user_id, current_revision_no, published_revision_id, valid_until,
                                   review_at, acl_version, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{workspaceId}, #{kbId}, #{slug}, #{title}, #{status}, #{freshness},
                    #{ownerUserId}, #{currentRevisionNo}, NULL, #{validUntil}, #{reviewAt}, 0, #{now}, #{now})
            """)
    int insertPage(Map<String, Object> params);

    @Insert("""
            INSERT INTO wiki_revision (id, page_id, revision_no, author_user_id, status, body, body_digest,
                                       summary, parent_revision_id, created_at)
            VALUES (#{id}, #{pageId}, #{revisionNo}, #{authorUserId}, #{status}, #{body}, #{bodyDigest},
                    #{summary}, #{parentRevisionId}, #{now})
            """)
    int insertRevision(Map<String, Object> params);

    @Update("""
            UPDATE wiki_revision SET body = #{body}, body_digest = #{bodyDigest}, summary = #{summary}
            WHERE id = #{id} AND status = 'DRAFT'
            """)
    int updateRevisionBody(@Param("id") String id, @Param("body") String body,
                           @Param("bodyDigest") String bodyDigest, @Param("summary") String summary);

    /** 原地改草稿：current_revision_no 不变，只递增治理位（If-Match 仍是同一个修订号）。 */
    @Update("""
            UPDATE wiki_page SET title = #{title}, acl_version = acl_version + 1, updated_at = #{now}
            WHERE id = #{pageId} AND current_revision_no = #{expectedRevisionNo}
            """)
    int updatePageTitleInPlace(@Param("pageId") String pageId, @Param("title") String title,
                               @Param("expectedRevisionNo") int expectedRevisionNo,
                               @Param("now") Instant now);

    /** 开新修订：与 INSERT wiki_revision 同事务，条件不满足即 0 行 → WIKI_REVISION_LOST。 */
    @Update("""
            UPDATE wiki_page SET title = #{title}, current_revision_no = current_revision_no + 1,
                   acl_version = acl_version + 1, updated_at = #{now}
            WHERE id = #{pageId} AND current_revision_no = #{expectedRevisionNo}
            """)
    int appendRevision(@Param("pageId") String pageId, @Param("title") String title,
                       @Param("expectedRevisionNo") int expectedRevisionNo, @Param("now") Instant now);

    /** 同一修订重复挂接同一来源不产生第二行（uk_wiki_source）。 */
    @Insert("""
            INSERT INTO wiki_source (id, tenant_id, revision_id, source_type, source_id, source_version,
                                     chunk_index, locator, content_digest, acl_version, created_at)
            VALUES (#{id}, #{tenantId}, #{revisionId}, #{sourceType}, #{sourceId}, #{sourceVersion},
                    #{chunkIndex}, #{locator}, #{contentDigest}, #{aclVersion}, #{now})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertSource(Map<String, Object> params);

    @Delete("""
            DELETE FROM wiki_source WHERE id = #{id} AND revision_id = #{revisionId}
            """)
    int deleteSource(@Param("id") String id, @Param("revisionId") String revisionId);

    @Update("""
            UPDATE wiki_page SET status = #{toStatus}, acl_version = acl_version + 1, updated_at = #{now}
            WHERE id = #{pageId} AND status = #{fromStatus}
            """)
    int transitionPage(@Param("pageId") String pageId, @Param("fromStatus") String fromStatus,
                       @Param("toStatus") String toStatus, @Param("now") Instant now);

    /** 通过：状态与可见性指针一次改到位，指针移动必须与 wiki.approve 审计同事务（契约 §1）。 */
    @Update("""
            UPDATE wiki_page SET status = 'PUBLISHED', published_revision_id = #{revisionId},
                   acl_version = acl_version + 1, updated_at = #{now}
            WHERE id = #{pageId} AND status = #{fromStatus}
              AND current_revision_no = #{revisionNo}
            """)
    int publishPage(@Param("pageId") String pageId, @Param("fromStatus") String fromStatus,
                    @Param("revisionId") String revisionId, @Param("revisionNo") int revisionNo,
                    @Param("now") Instant now);

    @Update("""
            UPDATE wiki_revision SET status = #{toStatus} WHERE id = #{revisionId} AND status = #{fromStatus}
            """)
    int transitionRevision(@Param("revisionId") String revisionId, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus);

    @Update("""
            UPDATE wiki_revision SET reviewer_user_id = #{reviewerUserId}, reviewed_at = #{reviewedAt},
                   decision_note_digest = #{decisionNoteDigest}
            WHERE id = #{revisionId} AND status = 'PENDING'
            """)
    int markReviewed(@Param("revisionId") String revisionId, @Param("reviewerUserId") String reviewerUserId,
                     @Param("reviewedAt") Instant reviewedAt,
                     @Param("decisionNoteDigest") String decisionNoteDigest);

    /** (page_id, revision_no, operation) 唯一：重复审核通过不会写第二份同步任务。 */
    @Insert("""
            INSERT INTO wiki_sync_task (id, tenant_id, page_id, revision_no, operation, status, attempts,
                                        max_attempts, next_run_at, correlation_id, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{pageId}, #{revisionNo}, #{operation}, 'PENDING', 0, #{maxAttempts},
                    #{nextRunAt}, #{correlationId}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertSyncTask(Map<String, Object> params);

    /**
     * 治理位更新：If-Match 打在 acl_version 上（error-codes.md 里 VERSION_CONFLICT 的产生点）。
     * clear 标志位区分"不改"与"显式清空"，避免用 null 兼职两种语义。
     */
    @Update("""
            UPDATE wiki_page
            SET owner_user_id = COALESCE(#{ownerUserId}, owner_user_id),
                valid_until = CASE WHEN #{clearValidUntil} THEN NULL
                                    ELSE COALESCE(#{validUntil}, valid_until) END,
                review_at = CASE WHEN #{clearReviewAt} THEN NULL
                                 ELSE COALESCE(#{reviewAt}, review_at) END,
                acl_version = acl_version + 1,
                updated_at = #{now}
            WHERE id = #{pageId} AND acl_version = #{expectedAclVersion}
            """)
    int patchGovernance(@Param("pageId") String pageId,
                        @Param("expectedAclVersion") long expectedAclVersion,
                        @Param("ownerUserId") String ownerUserId,
                        @Param("validUntil") Instant validUntil,
                        @Param("clearValidUntil") boolean clearValidUntil,
                        @Param("reviewAt") Instant reviewAt,
                        @Param("clearReviewAt") boolean clearReviewAt,
                        @Param("now") Instant now);
}
