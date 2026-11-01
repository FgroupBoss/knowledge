package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Wiki 读取侧。列一律显式 `AS` 驼峰别名（AGENTS.md §6），不依赖隐式映射。
 * 可空整数用包装类型，避免 NULL 构造器映射失败。
 */
@Mapper
public interface WikiQueryMapper {

    record PageRow(String id, String tenantId, String workspaceId, String kbId, String slug, String title,
                   String status, String freshness, String ownerUserId, int currentRevisionNo,
                   String publishedRevisionId, Integer publishedRevisionNo, Instant validUntil,
                   Instant reviewAt, long aclVersion, Instant createdAt, Instant updatedAt) {
    }

    record RevisionRow(String id, String pageId, int revisionNo, String authorUserId, String status,
                       String body, String bodyDigest, String summary, String reviewerUserId,
                       Instant reviewedAt, String decisionNoteDigest, String parentRevisionId,
                       Instant createdAt) {
    }

    record SourceRow(String id, String tenantId, String revisionId, String sourceType, String sourceId,
                     Integer sourceVersion, Integer chunkIndex, String locator, String contentDigest,
                     long aclVersion, Instant createdAt) {
    }

    record TaskRow(String id, String tenantId, String pageId, int revisionNo, String operation,
                   String status, int attempts, int maxAttempts, Instant nextRunAt, String errorCode,
                   String errorReason, Instant createdAt) {
    }

    record QueueRow(String revisionId, String pageId, String title, int revisionNo, String authorUserId,
                    Instant submittedAt) {
    }

    String PAGE_COLUMNS = "p.id, p.tenant_id AS tenantId, p.workspace_id AS workspaceId, "
            + "p.kb_id AS kbId, p.slug, p.title, p.status, p.freshness, "
            + "p.owner_user_id AS ownerUserId, p.current_revision_no AS currentRevisionNo, "
            + "p.published_revision_id AS publishedRevisionId, pub.revision_no AS publishedRevisionNo, "
            + "p.valid_until AS validUntil, p.review_at AS reviewAt, p.acl_version AS aclVersion, "
            + "p.created_at AS createdAt, p.updated_at AS updatedAt";

    String REVISION_COLUMNS = "id, page_id AS pageId, revision_no AS revisionNo, "
            + "author_user_id AS authorUserId, status, body, body_digest AS bodyDigest, summary, "
            + "reviewer_user_id AS reviewerUserId, reviewed_at AS reviewedAt, "
            + "decision_note_digest AS decisionNoteDigest, parent_revision_id AS parentRevisionId, "
            + "created_at AS createdAt";

    String SOURCE_COLUMNS = "id, tenant_id AS tenantId, revision_id AS revisionId, "
            + "source_type AS sourceType, source_id AS sourceId, source_version AS sourceVersion, "
            + "chunk_index AS chunkIndex, locator, content_digest AS contentDigest, "
            + "acl_version AS aclVersion, created_at AS createdAt";

    @Select("SELECT " + PAGE_COLUMNS + """
             FROM wiki_page p
             LEFT JOIN wiki_revision pub ON pub.id = p.published_revision_id
             WHERE p.id = #{pageId}
            """)
    PageRow findPage(@Param("pageId") String pageId);

    @Select("SELECT " + PAGE_COLUMNS + """
             FROM wiki_page p
             LEFT JOIN wiki_revision pub ON pub.id = p.published_revision_id
             WHERE p.kb_id = #{kbId} AND p.slug = #{slug}
            """)
    PageRow findPageBySlug(@Param("kbId") String kbId, @Param("slug") String slug);

    /** 游标按 id 字典序 keyset：UUID 随机但稳定，翻页不会重复也不会跳过（不使用 OFFSET）。 */
    @Select("""
            <script>
            SELECT p.id, p.tenant_id AS tenantId, p.workspace_id AS workspaceId, p.kb_id AS kbId,
                   p.slug, p.title, p.status, p.freshness, p.owner_user_id AS ownerUserId,
                   p.current_revision_no AS currentRevisionNo, p.published_revision_id AS publishedRevisionId,
                   NULL AS publishedRevisionNo, p.valid_until AS validUntil, p.review_at AS reviewAt,
                   p.acl_version AS aclVersion, p.created_at AS createdAt, p.updated_at AS updatedAt
            FROM wiki_page p
            WHERE p.tenant_id = #{tenantId} AND p.kb_id = #{kbId}
              AND (#{cursor,jdbcType=VARCHAR} IS NULL OR p.id &gt; #{cursor})
            <if test="status != null">AND p.status = #{status}</if>
            <if test="freshness != null">AND p.freshness = #{freshness}</if>
            <if test="owner != null">AND p.owner_user_id = #{owner}</if>
            ORDER BY p.id
            LIMIT #{limit}
            </script>
            """)
    List<PageRow> listPages(@Param("tenantId") String tenantId, @Param("kbId") String kbId,
                            @Param("status") String status, @Param("freshness") String freshness,
                            @Param("owner") String owner, @Param("cursor") String cursor,
                            @Param("limit") int limit);

    @Select("SELECT " + REVISION_COLUMNS + " FROM wiki_revision WHERE id = #{revisionId}")
    RevisionRow findRevision(@Param("revisionId") String revisionId);

    @Select("SELECT " + REVISION_COLUMNS + """
            FROM wiki_revision WHERE page_id = #{pageId} AND revision_no = #{revisionNo}
            """)
    RevisionRow findRevisionByNo(@Param("pageId") String pageId, @Param("revisionNo") int revisionNo);

    /** 修订历史倒序；游标是上一页最后一个 revision_no。 */
    @Select("""
            <script>
            SELECT id, page_id AS pageId, revision_no AS revisionNo, author_user_id AS authorUserId,
                   status, NULL AS body, body_digest AS bodyDigest, summary,
                   reviewer_user_id AS reviewerUserId, reviewed_at AS reviewedAt,
                   decision_note_digest AS decisionNoteDigest,
                   parent_revision_id AS parentRevisionId, created_at AS createdAt
            FROM wiki_revision
            WHERE page_id = #{pageId}
              AND (#{cursor,jdbcType=INTEGER} IS NULL OR revision_no &lt; #{cursor})
            ORDER BY revision_no DESC
            LIMIT #{limit}
            </script>
            """)
    List<RevisionRow> listRevisions(@Param("pageId") String pageId, @Param("cursor") Integer cursor,
                                    @Param("limit") int limit);

    @Select("SELECT " + SOURCE_COLUMNS + """
            FROM wiki_source WHERE revision_id = #{revisionId} ORDER BY source_type, source_id, source_version
            """)
    List<SourceRow> listSources(@Param("revisionId") String revisionId);

    /** 批量取多个修订的来源，避免"修订列表 → 逐条查来源"的 N+1。 */
    @Select("""
            <script>
            SELECT """ + SOURCE_COLUMNS + """
            FROM wiki_source
            WHERE revision_id IN
            <foreach item="id" collection="revisionIds" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY revision_id, source_type, source_id, source_version
            </script>
            """)
    List<SourceRow> listSourcesFor(@Param("revisionIds") List<String> revisionIds);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM wiki_source WHERE id = #{id}")
    SourceRow findSource(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM wiki_source WHERE revision_id = #{revisionId}")
    int countSources(@Param("revisionId") String revisionId);

    /** 页面上最近一次同步任务：W1 只会停在 PENDING，视图要如实显示"消费者未接线"。 */
    @Select("""
            SELECT id, tenant_id AS tenantId, page_id AS pageId, revision_no AS revisionNo, operation,
                   status, attempts, max_attempts AS maxAttempts, next_run_at AS nextRunAt,
                   error_code AS errorCode, error_reason AS errorReason, created_at AS createdAt
            FROM wiki_sync_task WHERE page_id = #{pageId} ORDER BY created_at DESC, id DESC LIMIT 1
            """)
    TaskRow latestTask(@Param("pageId") String pageId);

    @Select("""
            SELECT id, tenant_id AS tenantId, page_id AS pageId, revision_no AS revisionNo, operation,
                   status, attempts, max_attempts AS maxAttempts, next_run_at AS nextRunAt,
                   error_code AS errorCode, error_reason AS errorReason, created_at AS createdAt
            FROM wiki_sync_task
            WHERE page_id = #{pageId} AND revision_no = #{revisionNo} AND operation = #{operation}
            """)
    TaskRow findTask(@Param("pageId") String pageId, @Param("revisionNo") int revisionNo,
                     @Param("operation") String operation);

    /** 待审队列：只看 PENDING 修订，正文不出库（列表只给标题与时间）。 */
    @Select("""
            <script>
            SELECT r.id AS revisionId, r.page_id AS pageId, p.title, r.revision_no AS revisionNo,
                   r.author_user_id AS authorUserId, r.created_at AS submittedAt
            FROM wiki_revision r
            JOIN wiki_page p ON p.id = r.page_id
            WHERE p.tenant_id = #{tenantId} AND p.workspace_id = #{workspaceId}
              AND r.status = 'PENDING'
              AND p.status = 'IN_REVIEW'
              AND (#{cursor,jdbcType=VARCHAR} IS NULL OR CONCAT(UNIX_TIMESTAMP(r.created_at) * 1000, ':', r.id) &gt; #{cursor})
            ORDER BY r.created_at, r.id
            LIMIT #{limit}
            </script>
            """)
    List<QueueRow> reviewQueue(@Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId,
                               @Param("cursor") String cursor, @Param("limit") int limit);

    /** 指派表存在即具备审核能力（D-05：不新增角色枚举）。 */
    @Select("""
            SELECT COUNT(*) FROM wiki_review_assignment
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
              AND ((scope_type = 'KB' AND scope_id = #{kbId}) OR (scope_type = 'WORKSPACE' AND scope_id = #{workspaceId}))
            """)
    int reviewAssigned(@Param("tenantId") String tenantId, @Param("userId") String userId,
                       @Param("workspaceId") String workspaceId, @Param("kbId") String kbId);

    @Select("""
            SELECT COUNT(*) FROM wiki_page WHERE tenant_id = #{tenantId} AND kb_id = #{kbId}
              AND status IN ('DRAFT','IN_REVIEW','PUBLISHED','RETRACTING')
            """)
    int countPagesInKb(@Param("tenantId") String tenantId, @Param("kbId") String kbId);
}
