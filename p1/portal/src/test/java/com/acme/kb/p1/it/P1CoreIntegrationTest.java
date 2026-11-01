package com.acme.kb.p1.it;

import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.authz.AclCacheService;
import com.acme.kb.p1.authz.AclDecision;
import com.acme.kb.p1.chat.ChatWriteTx;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.pipeline.RetryPump;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心链路的集成证据（需要本地 MySQL/Redis/Kafka 容器）：
 * - #4 审计与业务同事务、失败回滚、链与 outbox 一致；
 * - #18 认领必须等 next_run_at 到期、租约续期与重试泵去重；
 * - #5 撤权后旧版本缓存即使还在也不可命中；
 * - #3 REFUSED/NO_EVIDENCE 绝不落引用行。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class P1CoreIntegrationTest {

    private static final String DB_URL = env("KB_TEST_DB_URL",
            "jdbc:mysql://127.0.0.1:13306/kb_p1_test?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8&connectionTimeZone=%2B00:00");
    private static final String APP_USER = env("KB_TEST_APP_USER", "kb_p1_app");
    private static final String APP_PASSWORD = TestSecrets.secret("KB_TEST_APP_PASSWORD");
    private static final String DDL_USER = env("KB_TEST_DDL_USER", "kb_p1_ddl");
    private static final String DDL_PASSWORD = TestSecrets.secret("KB_TEST_DDL_PASSWORD");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AuditService audit;
    @Autowired
    private PipelineCommandMapper pipeline;
    @Autowired
    private RetryPump retryPump;
    @Autowired
    private AclCacheService aclCache;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ChatWriteTx chatWriteTx;
    @Autowired
    private KbProperties props;

    // ---------- #4 审计事务化 ----------

    @Test
    void businessWriteAndAuditCommitTogether() {
        String tenantId = tenant();
        Actor actor = actor(tenantId);
        String workspaceId = UUID.randomUUID().toString();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            insertWorkspace(workspaceId, tenantId, "审计联动");
            audit.recordInTx(actor, "test.workspace_create", "workspace", workspaceId, "ALLOW", null,
                    Map.of("probe", "tx"));
        });

        assertEquals(1, count("SELECT COUNT(*) FROM workspace WHERE id = ?", workspaceId));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?",
                tenantId, "test.workspace_create"));
        assertEquals(1, count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ? AND topic = ?",
                tenantId, props.audit().topic()));
        assertTrue(audit.verifyChain(tenantId), "同事务写入的审计链必须自洽");
    }

    @Test
    void auditFailureRollsBackBusinessWrite() {
        String tenantId = tenant();
        Actor actor = actor(tenantId);
        String workspaceId = UUID.randomUUID().toString();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // action 为 null 触发 audit_event 非空约束失败，事务必须整体回滚
        assertThrows(ApiException.class, () -> tx.executeWithoutResult(status -> {
            insertWorkspace(workspaceId, tenantId, "应回滚");
            audit.recordInTx(actor, null, "workspace", workspaceId, "ALLOW", null, Map.of());
        }));

        assertEquals(0, count("SELECT COUNT(*) FROM workspace WHERE id = ?", workspaceId),
                "审计失败时业务写必须回滚");
        assertEquals(0, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ?", tenantId));
        assertEquals(0, count("SELECT COUNT(*) FROM audit_head WHERE tenant_id = ?", tenantId),
                "失败审计不能推进链头");
    }

    // ---------- #18 真退避 ----------

    @Test
    void claimWaitsForDueTimeAndRespectsLease() {
        String tenantId = tenant();
        String operationId = UUID.randomUUID().toString();
        String ownerA = "worker-a-" + UUID.randomUUID();
        String ownerB = "worker-b-" + UUID.randomUUID();
        String taskId = insertTask(tenantId, operationId, "RETRY_SCHEDULED", 1,
                Instant.now().plusSeconds(30));
        Instant now = Instant.now();

        assertEquals(0, pipeline.claimTask(operationId, ownerA, now.plusSeconds(40), now),
                "next_run_at 未到期不得认领");
        assertEquals(1, pipeline.claimTask(operationId, ownerA, now.plusSeconds(40),
                now.plusSeconds(31)));
        assertEquals(0, pipeline.claimTask(operationId, ownerB, now.plusSeconds(80),
                now.plusSeconds(35)), "租约有效期内他方不得抢");
        assertEquals(1, pipeline.claimTask(operationId, ownerB, now.plusSeconds(200),
                now.plusSeconds(120)), "租约过期后可被重新认领");

        assertEquals(1, pipeline.heartbeat(ownerB, now.plusSeconds(300), now.plusSeconds(130)));
        assertEquals(0, pipeline.heartbeat(ownerA, now.plusSeconds(300), now.plusSeconds(130)));
        assertEquals(0, pipeline.heartbeat(ownerB, now.plusSeconds(300), now.plusSeconds(301)),
                "过期租约不能被心跳复活");
        assertEquals(3, count("SELECT attempts FROM ingest_task WHERE id = ?", taskId),
                "每次认领恰好推进一次 attempts");
    }

    @Test
    void retryPumpNotifiesDueTasksExactlyOnce() throws Exception {
        String tenantId = tenant();
        String documentId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        String taskId = insertTask(tenantId, documentId, operationId, "RETRY_SCHEDULED", 1,
                Instant.now().minusSeconds(2));

        retryPump.notifyDueRetries();
        retryPump.notifyDueRetries();

        assertEquals(1, count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", documentId),
                "同一 taskId+attempts 只能通知一次");
        String payload = jdbc.queryForObject(
                "SELECT CAST(payload AS CHAR) FROM outbox_event WHERE aggregate_id = ?", String.class,
                documentId);
        String expectedEventId = UUID.nameUUIDFromBytes((taskId + ":1").getBytes(StandardCharsets.UTF_8))
                .toString();
        assertTrue(payload.contains(expectedEventId), "退避通知必须使用确定性 eventId");
        assertEquals(props.ingest().topicRetry(), jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregate_id = ?", String.class, documentId));

        String futureDoc = UUID.randomUUID().toString();
        insertTask(tenantId, futureDoc, UUID.randomUUID().toString(), "RETRY_SCHEDULED", 1,
                Instant.now().plusSeconds(3600));
        retryPump.notifyDueRetries();
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", futureDoc),
                "未到期任务不得提前通知");
    }

    // ---------- #5 ACL 版本缓存 ----------

    @Test
    void staleAclVersionKeyCanNeverBeRead() {
        String kbId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();

        aclCache.poisonWithStaleDecision(kbId, userId, 3, "VIEWER");
        assertTrue(aclCache.find(kbId, userId, 4).isEmpty(),
                "旧版本决策即使还在 Redis 也不得命中");

        aclCache.invalidate(kbId, 4, List.of(userId));
        assertFalse(Boolean.TRUE.equals(redis.hasKey("p1:acl:" + kbId + ":" + userId + ":v3")),
                "清理应删掉指针指向的旧版本键");

        aclCache.store(new AclDecision(kbId, userId, "tenant", "workspace", "VIEWER",
                Set.of("kb:read"), "INTERNAL", 4, true, System.currentTimeMillis()));
        assertTrue(aclCache.find(kbId, userId, 4).isPresent(), "同版本应命中");
        assertTrue(aclCache.find(kbId, userId, 5).isEmpty(), "撤权后新版本必须回源");
    }

    // ---------- #3 拒答零引用 ----------

    @Test
    void refusedAndNoEvidencePersistZeroCitations() {
        String tenantId = tenant();
        Actor actor = actor(tenantId);
        String kbId = UUID.randomUUID().toString();

        Started refused = startChat(actor, kbId, "拒绝应零引用");
        chatWriteTx.complete(actor, completion(actor, refused, "REFUSED", "MODEL_NO_ANSWER", 1),
                List.of(draft(kbId)));
        assertEquals(0, count("SELECT COUNT(*) FROM citation WHERE request_id = ?", refused.requestId()));
        assertEquals("REFUSED", jdbc.queryForObject(
                "SELECT status FROM chat_request WHERE id = ?", String.class, refused.requestId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND action = ?",
                refused.requestId(), "chat.refused"));

        Started noEvidence = startChat(actor, kbId, "无证据应零引用");
        chatWriteTx.complete(actor, completion(actor, noEvidence, "NO_EVIDENCE", "NO_EVIDENCE", 1),
                List.of(draft(kbId)));
        assertEquals(0, count("SELECT COUNT(*) FROM citation WHERE request_id = ?", noEvidence.requestId()));

        Started answered = startChat(actor, kbId, "正常回答");
        chatWriteTx.complete(actor, completion(actor, answered, "ANSWERED", null, 1),
                List.of(draft(kbId)));
        assertEquals(1, count("SELECT COUNT(*) FROM citation WHERE request_id = ?", answered.requestId()));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE resource_id = ? AND action = ?",
                answered.requestId(), "chat.answer"));
    }

    // ---------- helpers ----------

    private String tenant() {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO tenant (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
                id, "t-" + id.substring(0, 8), "租户", Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    private void insertWorkspace(String id, String tenantId, String name) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO workspace (id, tenant_id, code, name, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?)",
                id, tenantId, "ws-" + id.substring(0, 8), name, Timestamp.from(now), Timestamp.from(now));
    }

    private static Actor actor(String tenantId) {
        return new Actor(UUID.randomUUID().toString(), tenantId, "tenant", "user", "测试用户",
                Classification.INTERNAL);
    }

    private String insertTask(String tenantId, String operationId, String status, int attempts,
                              Instant nextRunAt) {
        return insertTask(tenantId, UUID.randomUUID().toString(), operationId, status, attempts, nextRunAt);
    }

    private String insertTask(String tenantId, String documentId, String operationId, String status,
                              int attempts, Instant nextRunAt) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ingest_task (id, tenant_id, document_id, document_ver, operation_id, "
                        + "task_type, status, attempts, max_attempts, next_run_at, correlation_id, "
                        + "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, documentId, 1, operationId, "INGEST", status, attempts, 5,
                Timestamp.from(nextRunAt), UUID.randomUUID().toString(), Timestamp.from(now),
                Timestamp.from(now));
        return id;
    }

    private record Started(String requestId, String messageId, String conversationId) {
    }

    private Started startChat(Actor actor, String kbId, String question) {
        String requestId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        chatWriteTx.start(new ChatWriteTx.Start(requestId, UUID.randomUUID().toString(), messageId,
                actor.tenantId(), UUID.randomUUID().toString(), actor.userId(), kbId, conversationId,
                7, null, question, UUID.randomUUID().toString(), "会话"));
        return new Started(requestId, messageId, conversationId);
    }

    private static ChatWriteTx.Completion completion(Actor actor, Started started, String status,
                                                     String reason, int candidates) {
        return new ChatWriteTx.Completion(started.requestId(), started.messageId(), actor.tenantId(),
                started.conversationId(), status, reason,
                status.equals("ANSWERED") ? "回答 [1]" : "拒答", "extractive", "digest",
                "p1-chat-v1", "config", 1, candidates, 0, 0, 0, 0, 3, 5, 1L, 9L, 10, 5, null);
    }

    private static ChatWriteTx.CitationDraft draft(String kbId) {
        return new ChatWriteTx.CitationDraft(UUID.randomUUID().toString(), 1, kbId, "edoc", "echunk",
                "文档", 1, "节", 0, 10, "片段", "d".repeat(64), 0.8, 7);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
