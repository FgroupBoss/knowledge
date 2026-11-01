package com.acme.kb.p1.it;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.wiki.WikiWriteTx;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乐观锁与并发集成测试（契约 `state-machine-wiki.md` §5 用例 3/4/5/6/12）。
 *
 * 并发对由屏障对齐起跑，**谁先拿到行锁由 MySQL 决定**，所以断言只落在不变量上
 * （恰一个成功、终态唯一、不产生第二行），不假设输赢顺序——顺序断言在 CI 上是随机失败源。
 */
class WikiConcurrencyIntegrationTest extends P1WikiTestContext {

    /** 一次并发调用的结果：成功带返回值，失败带原始异常。 */
    private record Attempt(Object value, Throwable error) {

        boolean succeeded() {
            return error == null;
        }

        ErrorCode code() {
            assertInstanceOf(ApiException.class, error, "并发失败必须转成 ApiException，绝不静默覆盖");
            return ((ApiException) error).code();
        }
    }

    @Test
    void staleIfMatchSaveReturnsRevisionLostWithoutLeakingBody() {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);
        wiki.saveDraft(s.author(), published.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "他人的正文内容", null));

        ApiException error = assertThrows(ApiException.class, () -> wiki.saveDraft(s.author(),
                published.id(), "1", new Dto.WikiDraftRequest("治理页面", "我的正文内容", null)));
        assertEquals(ErrorCode.WIKI_REVISION_LOST, error.code());
        Map<String, String> detail = error.details().get(0);
        assertEquals("2", detail.get("currentRevisionNo"));
        assertEquals("2", detail.get("latestRevisionNo"));
        String digest = jdbc.queryForObject("SELECT body_digest FROM wiki_revision WHERE page_id = ?"
                + " AND revision_no = 2", String.class, published.id());
        assertEquals(digest.substring(0, 12), detail.get("latestBodyDigest12"));
        assertTrue(detail.values().stream().noneMatch(value -> value.contains("他人的正文内容")),
                "响应只给计数与摘要前 12 位，绝不回传他人正文（用例 3）");

        assertEquals("他人的正文内容", jdbc.queryForObject(
                "SELECT body FROM wiki_revision WHERE page_id = ? AND revision_no = 2", String.class,
                published.id()), "冲突的一方不得覆盖已提交的草稿");
        assertEquals(2, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ?", published.id()));
        assertEquals(0, metadataHits(s.tenantId(), "我的正文内容"));
    }

    /** 用例 4：两个并发 `submit` 同一页 → 恰一个成功，且不会多出第二个修订行。 */
    @Test
    void concurrentSubmitsOnTheSamePageYieldExactlyOneWinner() throws Exception {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "并发提交");

        List<Attempt> attempts = concurrently(
                () -> wiki.submit(s.author(), page.id(), "1", key()),
                () -> wiki.submit(s.author(), page.id(), "1", key()));

        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count(), "恰一个成功");
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, failing(attempts).code());
        assertEquals("IN_REVIEW", pageStatus(page.id()));
        assertEquals("PENDING", revisionStatus(page.id(), 1));
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ?", page.id()),
                "wiki_revision 无第三行");
        assertEquals(1, auditCount(s.tenantId(), "wiki.submit", "ALLOW"));
        assertEquals(1, auditCount(s.tenantId(), "wiki.submit", "DENY"), "被拒的一方必须有拒绝审计");
    }

    /** 用例 6：重复 approve 同一修订必须幂等——不产生新修订，也不重复排同步任务。 */
    @Test
    void repeatedApproveIsIdempotent() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "重复审核");
        String revisionId = currentRevisionId(page.id());
        wiki.submit(s.author(), page.id(), "1", key());

        String approveKey = key();
        Dto.WikiPageView first = wiki.approve(s.reviewer(), revisionId, approveKey);
        Dto.WikiPageView sameKey = wiki.approve(s.reviewer(), revisionId, approveKey);
        Dto.WikiPageView newKey = wiki.approve(s.reviewer(), revisionId, key());

        assertEquals(first.id(), sameKey.id());
        assertEquals(1, first.publishedRevisionNo());
        assertEquals(1, newKey.publishedRevisionNo());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ?", page.id()));
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?",
                page.id(), WikiWriteTx.OP_PUBLISH));
        assertEquals(1, count("SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ? AND scope = ?",
                s.tenantId(), WikiWriteTx.SCOPE_APPROVE));
        // 同键重放在服务层短路（不写审计）；换新键才走"已生效"幂等分支，各留一条可核对的 ALLOW
        assertEquals(2, auditCount(s.tenantId(), "wiki.approve", "ALLOW"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?"
                        + " AND metadata_json LIKE '%idempotent-replay%'", s.tenantId(), "wiki.approve"));
        assertEquals(0, auditCount(s.tenantId(), "wiki.approve", "DENY"));
    }

    /** `VERSION_CONFLICT` 的产生点：治理位 If-Match 打在 `acl_version` 上，冲突方不留半笔改动。 */
    @Test
    void staleGovernanceIfMatchReturnsVersionConflict() {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "并发治理位");
        wiki.governance(s.author(), page.id(), "0", Map.of("validUntil", "2027-01-01T00:00:00Z"));

        ApiException error = assertThrows(ApiException.class, () -> wiki.governance(s.author(), page.id(),
                "0", Map.of("validUntil", "2028-01-01T00:00:00Z")));
        assertEquals(ErrorCode.VERSION_CONFLICT, error.code());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_page WHERE id = ? AND acl_version = 1"
                        + " AND valid_until = ?", page.id(),
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z"))));
        assertEquals(1, auditCount(s.tenantId(), "wiki.validity.update", "ALLOW"));
    }

    /** 两个线程拿同一个 If-Match 改治理位：恰一个成功，治理位只前进 1。 */
    @Test
    void concurrentGovernancePatchesAllowOnlyOneWriter() throws Exception {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "并发治理位");

        List<Attempt> attempts = concurrently(
                () -> wiki.governance(s.author(), page.id(), "0", Map.of("reviewAt", "2026-12-01T00:00:00Z")),
                () -> wiki.governance(s.author(), page.id(), "0", Map.of("reviewAt", "2027-12-01T00:00:00Z")));

        assertEquals(1, attempts.stream().filter(Attempt::succeeded).count());
        assertEquals(ErrorCode.VERSION_CONFLICT, failing(attempts).code());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_page WHERE id = ? AND acl_version = 1", page.id()));
        assertEquals(1, auditCount(s.tenantId(), "wiki.validity.update", "ALLOW"));
    }

    /**
     * 用例 5 的**状态轴半边**：approve 与 retract 并发时，页面终态唯一、生效修订至多一个、
     * 下线中的页面不给正文。索引侧（引擎里是否真的只剩一份有效索引）需 F-1 真引擎复测，本轮不声称。
     */
    @Test
    void approveAndRetractConcurrentlyLeaveASingleEffectiveState() throws Exception {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);
        wiki.saveDraft(s.author(), published.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "第一段\n第二段\n第三段", null));
        wiki.addSource(s.author(), published.id(), new Dto.WikiSourceRequest("DOCUMENT",
                readyDocument(s, s.authorId()), 1, 0, "第 2 节"));
        wiki.submit(s.author(), published.id(), "2", key());

        List<Attempt> attempts = concurrently(
                () -> wiki.approve(s.reviewer(), currentRevisionId(published.id()), key()),
                () -> wiki.retract(s.author(), published.id(), key()));

        String status = pageStatus(published.id());
        assertTrue(List.of("PUBLISHED", "RETRACTING").contains(status),
                "页面只能落在一个审核态，实际：" + status);
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ? AND status = 'APPROVED'",
                published.id()), "绝不出现两个生效修订");

        int approvedRevisionNo = jdbc.queryForObject("SELECT MAX(revision_no) FROM wiki_revision"
                + " WHERE page_id = ? AND status = 'APPROVED'", Integer.class, published.id());
        assertEquals(approvedRevisionNo, pointerRevisionNo(published.id()), "指针必须指向那个唯一生效修订");
        if ("RETRACTING".equals(status)) {
            assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?",
                    published.id(), WikiWriteTx.OP_RETRACT));
            assertNull(wiki.detail(s.author(), published.id()).body());
        } else {
            assertEquals(0, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?",
                    published.id(), WikiWriteTx.OP_RETRACT));
        }
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND revision_no = ?"
                + " AND operation = ?", published.id(), approvedRevisionNo, WikiWriteTx.OP_PUBLISH));
        assertTrue(attempts.stream().filter(a -> !a.succeeded()).count() <= 1,
                "状态机上没有让 approve 与 retract 双双失效的边");
        assertTrue(audit.verifyChain(s.tenantId()));
    }

    /** 用例 12 的数据层半边 + `uk_wiki_task`：桶键与任务键的唯一性由数据库保证，不靠进程内去重。 */
    @Test
    void reminderBucketAndSyncTaskKeysAreUniqueAtTheDatabaseLevel() {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "提醒桶");

        insertReminder(s, page.id(), "STALE", "2026-11-01");
        assertThrows(DuplicateKeyException.class,
                () -> insertReminder(s, page.id(), "STALE", "2026-11-01"));
        insertReminder(s, page.id(), "STALE", "2026-11-02");
        insertReminder(s, page.id(), "EXPIRING", "2026-11-01");
        assertEquals(3, count("SELECT COUNT(*) FROM wiki_reminder WHERE page_id = ?", page.id()),
                "换一天或换规则才是新桶（调度器属 W3，本轮只有唯一键这层保证）");

        insertSyncTask(s, page.id(), 1, WikiWriteTx.OP_PUBLISH);
        assertThrows(DuplicateKeyException.class,
                () -> insertSyncTask(s, page.id(), 1, WikiWriteTx.OP_PUBLISH));
        insertSyncTask(s, page.id(), 1, WikiWriteTx.OP_RETRACT);
        assertEquals(2, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ?", page.id()));
    }

    /** 并发挂接同一来源：`uk_wiki_source` + `ON DUPLICATE KEY UPDATE` 只留一行，且两侧拿到同一个 id。 */
    @Test
    void concurrentSourceAttachKeepsASingleRow() throws Exception {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "并发挂接来源");
        String documentId = readyDocument(s, s.authorId());
        String revisionId = currentRevisionId(page.id());

        List<Attempt> attempts = concurrently(
                () -> wiki.addSource(s.author(), page.id(), new Dto.WikiSourceRequest("DOCUMENT", documentId,
                        1, 0, "第 1 节")),
                () -> wiki.addSource(s.author(), page.id(), new Dto.WikiSourceRequest("DOCUMENT", documentId,
                        1, 0, "第 1 节")));

        assertEquals(2, attempts.stream().filter(Attempt::succeeded).count(), "重复挂接按幂等处理，不是冲突");
        List<String> ids = attempts.stream().map(a -> ((Dto.WikiSourceView) a.value()).id()).distinct().toList();
        assertEquals(1, ids.size(), "两个线程必须看到同一行");
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_source WHERE revision_id = ?", revisionId));
    }

    // ------------------------------------------------------------------ 辅助

    private int pointerRevisionNo(String pageId) {
        Integer value = jdbc.queryForObject("SELECT rev.revision_no FROM wiki_page p"
                + " JOIN wiki_revision rev ON rev.id = p.published_revision_id WHERE p.id = ?",
                Integer.class, pageId);
        return value == null ? -1 : value;
    }

    private void insertReminder(Space s, String pageId, String rule, String bucket) {
        jdbc.update("INSERT INTO wiki_reminder (id, tenant_id, page_id, rule, target_user_id, date_bucket,"
                        + " status, attempts, next_run_at, notified_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id(), s.tenantId(), pageId, rule, s.authorId(), bucket, "PENDING", 0, null, null,
                Timestamp.from(Instant.now()));
    }

    private void insertSyncTask(Space s, String pageId, int revisionNo, String operation) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO wiki_sync_task (id, tenant_id, page_id, revision_no, operation, status,"
                        + " attempts, max_attempts, next_run_at, correlation_id, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,'PENDING',0,6,?,?,?,?)",
                id(), s.tenantId(), pageId, revisionNo, operation, Timestamp.from(now), id(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private Attempt failing(List<Attempt> attempts) {
        return attempts.stream().filter(a -> !a.succeeded()).findFirst().orElseThrow();
    }

    /** 屏障对齐起跑；30 秒上限防止锁等待把套件挂死。 */
    private List<Attempt> concurrently(Callable<Object> first, Callable<Object> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> left = pool.submit(() -> attempt(barrier, first));
            Future<Attempt> right = pool.submit(() -> attempt(barrier, second));
            List<Attempt> out = new ArrayList<>();
            out.add(left.get(30, TimeUnit.SECONDS));
            out.add(right.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Attempt attempt(CyclicBarrier barrier, Callable<Object> call) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            return new Attempt(call.call(), null);
        } catch (ExecutionException e) {
            return new Attempt(null, e.getCause());
        } catch (Exception e) {
            return new Attempt(null, e);
        }
    }
}
