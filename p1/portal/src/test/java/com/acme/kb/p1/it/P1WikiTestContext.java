package com.acme.kb.p1.it;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.api.WikiController;
import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.ScopeCommandMapper;
import com.acme.kb.p1.wiki.WikiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wiki 集成测试的公共夹具：一套完整租户边界（3 个用户 + 1 个空间 + 1 个库 + 1 条审核指派 + 1 篇可引用文档）。
 * 每个用例都开新租户，所以断言可以按 tenant_id 精确计数，不受其它用例与演练遗留数据影响。
 *
 * 需要本地 MySQL/Redis/Kafka 容器（`bash p0/scripts/01-stack-up.sh`），凭据见 {@link TestSecrets}。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class P1WikiTestContext {

    private static final String DB_URL = env("KB_TEST_DB_URL",
            "jdbc:mysql://127.0.0.1:13306/kb_p1_test?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8&connectionTimeZone=%2B00:00");
    private static final String APP_USER = env("KB_TEST_APP_USER", "kb_p1_app");
    private static final String APP_PASSWORD = TestSecrets.secret("KB_TEST_APP_PASSWORD");
    private static final String DDL_USER = env("KB_TEST_DDL_USER", "kb_p1_ddl");
    private static final String DDL_PASSWORD = TestSecrets.secret("KB_TEST_DDL_PASSWORD");

    @DynamicPropertySource
    static void wikiProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DB_URL);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> DDL_USER);
        registry.add("spring.flyway.password", () -> DDL_PASSWORD);
        registry.add("kb.auth.dev-subject-enabled", () -> "true");
        registry.add("kb.role", () -> "worker");
        registry.add("kb.engine.provider", () -> "stub");
        registry.add("kb.engine.embedding-mode", () -> "deterministic");
        registry.add("kb.engine.chat-mode", () -> "extractive");
        registry.add("kb.storage.access-key", () -> TestSecrets.secret("KB_MINIO_ACCESS_KEY"));
        registry.add("kb.storage.secret-key", () -> TestSecrets.secret("KB_MINIO_SECRET_KEY"));
        registry.add("spring.data.redis.password", () -> TestSecrets.secret("KB_TEST_REDIS_PASSWORD"));
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("kb.ingest.retry-pump-ms", () -> "3600000");
        registry.add("kb.ingest.outbox-interval-ms", () -> "3600000");
        registry.add("kb.database.enforce-least-privilege", () -> "true");
        // 治理端点只在 kb.wiki.enabled=true 时注册（config-catalog-p1.md），用例里连路由一起验
        registry.add("kb.wiki.enabled", () -> "true");
    }

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected AuditService audit;
    @Autowired
    protected WikiService wiki;
    @Autowired
    protected ScopeCommandMapper scopes;
    @Autowired
    protected ApplicationContext ctx;
    @Autowired
    protected KbProperties props;

    /** 一个用例一套边界：author 可写、reviewer 已被指派、viewer 只能读。 */
    protected record Space(String tenantId, String workspaceId, String kbId,
                           String authorId, String reviewerId, String viewerId) {

        Actor author() {
            return actor(authorId, tenantId);
        }

        Actor reviewer() {
            return actor(reviewerId, tenantId);
        }

        Actor viewer() {
            return actor(viewerId, tenantId);
        }
    }

    protected Space space() {
        Instant now = Instant.now();
        String tenantId = id();
        String workspaceId = id();
        String kbId = id();
        String authorId = id();
        String reviewerId = id();
        String viewerId = id();
        scopes.insertTenant(tenantId, "t-" + tenantId.substring(0, 8), "试点租户", now);
        scopes.insertUser(authorId, tenantId, "http://localhost/idp", "author-" + authorId, "作者",
                Classification.INTERNAL.name(), now);
        scopes.insertUser(reviewerId, tenantId, "http://localhost/idp", "reviewer-" + reviewerId, "审核人",
                Classification.INTERNAL.name(), now);
        scopes.insertUser(viewerId, tenantId, "http://localhost/idp", "viewer-" + viewerId, "读者",
                Classification.INTERNAL.name(), now);
        scopes.insertWorkspace(workspaceId, tenantId, "ws-" + workspaceId.substring(0, 8), "试点空间",
                authorId, now);
        scopes.upsertMember(id(), workspaceId, authorId, "CONTRIBUTOR", now);
        // 审核人故意给 VIEWER：审核能力必须来自上面那条指派行，而不是角色（D-05）
        scopes.upsertMember(id(), workspaceId, reviewerId, "VIEWER", now);
        scopes.upsertMember(id(), workspaceId, viewerId, "VIEWER", now);
        scopes.insertKnowledgeBase(kbId, tenantId, workspaceId, "kb-" + kbId.substring(0, 8), "试点库",
                "weknora", Classification.INTERNAL.name(), now);
        // D-05：审核能力来自指派行，不来自新角色；引导 SQL 就是这一行（W1 无端点）
        Space space = new Space(tenantId, workspaceId, kbId, authorId, reviewerId, viewerId);
        assignReviewer(space, reviewerId);
        return space;
    }

    /** W1 没有指派端点，用例按 `p1/compose/README.md` 的引导 SQL 直接插行。 */
    protected void assignReviewer(Space s, String userId) {
        jdbc.update("INSERT INTO wiki_review_assignment (id, tenant_id, scope_type, scope_id, user_id,"
                        + " assigned_by, created_at) VALUES (?,?,?,?,?,?,?)",
                id(), s.tenantId(), "KB", s.kbId(), userId, s.authorId(), Timestamp.from(Instant.now()));
    }

    /** 断言状态轴时一律回库读，不用响应里的值（响应可能来自缓存视图）。 */
    protected String pageStatus(String pageId) {
        return jdbc.queryForObject("SELECT status FROM wiki_page WHERE id = ?", String.class, pageId);
    }

    protected String revisionStatus(String pageId, int revisionNo) {
        return jdbc.queryForObject("SELECT status FROM wiki_revision WHERE page_id = ? AND revision_no = ?",
                String.class, pageId, revisionNo);
    }

    /** 红线 1 的负向断言：正文与审核意见原文都不得出现在审计元数据里。 */
    protected int metadataHits(String tenantId, String needle) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ?"
                + " AND metadata_json LIKE ?", Integer.class, tenantId, "%" + needle + "%");
        return value == null ? 0 : value;
    }

    /** 来源文档：Wiki 页面的提交与发布都要靠它，状态必须是 READY 且当前版本。 */
    protected String readyDocument(Space s, String createdBy) {
        String documentId = id();
        Instant now = Instant.now();
        // document.uk_doc_kb_hash_version：同一库内正文摘要必须互不相同，否则第二个来源撞唯一键
        jdbc.update("INSERT INTO document (id, family_id, tenant_id, workspace_id, kb_id, display_name,"
                        + " version, object_key, sha256, size_bytes, mime_type, extension, classification,"
                        + " status, engine_doc_id, acl_enforced, created_by, created_at, updated_at)"
                        + " VALUES (?,?,?, ?,?,?,1,?,?,?,'text/plain','txt','INTERNAL','READY',NULL,0,?,?,?)",
                documentId, documentId, s.tenantId(), s.workspaceId(), s.kbId(), "来源文档",
                "kb-p1-test/" + documentId, Hashing.sha256Hex(documentId), 1024L, createdBy,
                Timestamp.from(now), Timestamp.from(now));
        return documentId;
    }

    /** 建一张草稿页并挂好来源，返回视图；用例从这里起步最常见。 */
    protected Dto.WikiPageView draftPageWithSource(Space s, String body) {
        Dto.WikiPageView page = draftPage(s, body);
        wiki.addSource(s.author(), page.id(), new Dto.WikiSourceRequest("DOCUMENT",
                readyDocument(s, s.authorId()), 1, 0, "第 1 节"));
        // create 的响应里没有后来挂上的来源，用例要断言来源列表必须回读一次
        return wiki.detail(s.author(), page.id());
    }

    protected Dto.WikiPageView draftPage(Space s, String body) {
        return wiki.create(s.author(), s.kbId(), key(), new Dto.WikiPageCreateRequest(
                "page-" + id().substring(0, 8), "治理页面", body, "一句话摘要", null, null, null));
    }

    protected Dto.WikiPageView publishedPage(Space s) {
        Dto.WikiPageView page = draftPageWithSource(s, "第一段\n第二段");
        wiki.submit(s.author(), page.id(), "1", key());
        return wiki.approve(s.reviewer(), currentRevisionId(page.id()), key());
    }

    protected String currentRevisionId(String pageId) {
        String revisionId = jdbc.queryForObject(
                "SELECT id FROM wiki_revision WHERE page_id = ? ORDER BY revision_no DESC LIMIT 1",
                String.class, pageId);
        return revisionId;
    }

    protected int auditCount(String tenantId, String action, String result) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ?"
                + " AND action = ? AND result = ?", Integer.class, tenantId, action, result);
        return value == null ? 0 : value;
    }

    protected int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    protected static String id() {
        return UUID.randomUUID().toString();
    }

    protected static Actor actor(String userId, String tenantId) {
        return new Actor(userId, tenantId, "tenant", "user-" + userId.substring(0, 8), "测试用户",
                Classification.INTERNAL);
    }

    /** 幂等键必须是 UUID（UploadValidator.requireIdempotencyKey）。 */
    protected static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void wikiRoutesAreRegisteredOnlyWhenTheFeatureIsEnabled() {
        // config-catalog-p1.md：关闭时不注册 Wiki 路由。这里是 enabled=true 的正向半边
        assertEquals(1, ctx.getBeanNamesForType(WikiController.class).length,
                "enabled=true 时 Wiki 路由必须注册恰好一次");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
