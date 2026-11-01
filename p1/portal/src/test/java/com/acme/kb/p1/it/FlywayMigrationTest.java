package com.acme.kb.p1.it;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #6 证据：迁移只用 DDL 账号跑通，运行账号只拿到 DML。
 * 需要本地 MySQL（默认 127.0.0.1:13306/kb_p1_test），可用环境变量覆盖。
 */
class FlywayMigrationTest {

    private static final String URL = env("KB_TEST_DB_URL",
            "jdbc:mysql://127.0.0.1:13306/kb_p1_test?useSSL=false&allowPublicKeyRetrieval=true"
                    + "&serverTimezone=UTC&characterEncoding=UTF-8");
    private static final String DDL_USER = env("KB_TEST_DDL_USER", "kb_p1_ddl");
    private static final String DDL_PASSWORD = TestSecrets.secret("KB_TEST_DDL_PASSWORD");
    private static final String APP_USER = env("KB_TEST_APP_USER", "kb_p1_app");
    private static final String APP_PASSWORD = TestSecrets.secret("KB_TEST_APP_PASSWORD");

    @Test
    void migrationsApplyAndValidateWithDdlAccount() {
        Flyway flyway = Flyway.configure()
                .dataSource(URL, DDL_USER, DDL_PASSWORD)
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .load();

        MigrateResult result = flyway.migrate();
        assertNotNull(result);
        MigrationInfo[] applied = flyway.info().applied();
        assertTrue(applied.length >= 18, "至少应有 V1..V18 共 18 个已应用版本，实际 " + applied.length);
        assertEquals(0, flyway.info().pending().length, "迁移后不应有 pending 版本");

        Set<String> versions = new HashSet<>();
        for (MigrationInfo info : applied) {
            versions.add(info.getVersion().getVersion());
            assertNotNull(info.getChecksum(), "版本 " + info.getVersion() + " 必须已校验");
        }
        for (int i = 1; i <= 18; i++) {
            assertTrue(versions.contains(String.valueOf(i)), "缺少迁移 V" + i);
        }
        flyway.validate();
    }

    @Test
    void coreTablesExistAfterMigration() throws SQLException {
        Flyway.configure().dataSource(URL, DDL_USER, DDL_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        Set<String> tables = tables();
        for (String required : new String[]{"tenant", "app_user", "workspace", "knowledge_base",
                "document", "ingest_task", "outbox_event", "audit_event", "audit_head",
                "SPRING_SESSION", "conversation", "chat_request", "citation", "chat_feedback",
                "reconciliation_run", "engine_kb", "engine_document", "engine_chunk"}) {
            assertTrue(tables.contains(required.toLowerCase()), "缺少表 " + required);
        }
        assertTrue(columnExists("chat_request", "dropped_by_unsupported_citation"),
                "拒答统计列缺失");
        assertTrue(columnExists("audit_head", "last_hash"), "审计链头缺 last_hash");
        assertTrue(columnExists("ingest_task", "retry_notified_at"), "退避通知水位列缺失");
    }

    /** P2-W1 的九张治理表与唯一键：唯一键是"重复索引 0"与"同 slug 两页"的数据库层保证。 */
    @Test
    void wikiGovernanceTablesAndKeysExistAfterMigration() throws SQLException {
        Flyway.configure().dataSource(URL, DDL_USER, DDL_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        Set<String> tables = tables();
        for (String required : new String[]{"wiki_page", "wiki_revision", "wiki_source",
                "wiki_review_assignment", "wiki_sync_task", "wiki_reminder", "notification",
                "correction_case", "wiki_staleness_run"}) {
            assertTrue(tables.contains(required), "缺少表 " + required);
        }
        // ADR-010：列名按 error-codes.md 的产生点写 acl_version，不采用设计文档散文里的 acl_version_guard
        assertTrue(columnExists("wiki_page", "acl_version"), "页面治理位列缺失");
        assertTrue(columnExists("wiki_page", "freshness"), "新鲜度派生位列缺失（W3 调度器写，本轮只建列）");
        assertTrue(columnExists("wiki_revision", "decision_note_digest"), "审核意见摘要哈希列缺失（红线 1）");
        assertTrue(columnExists("wiki_sync_task", "lease_owner"), "同步任务租约列缺失（S3/S5 依赖）");
        assertTrue(columnExists("wiki_reminder", "date_bucket"), "提醒幂等桶列缺失");
        assertTrue(columnExists("correction_case", "resolution_note_digest"), "工单处置说明必须只留摘要");
        assertTrue(columnExists("wiki_staleness_run", "counts_json"), "扫描批次证据列缺失");
        // W3 的扫描器靠这两处取候选与幂等，缺一个就只能全表扫或重复开单
        assertTrue(indexExists("wiki_page", "idx_wiki_page_fresh"), "新鲜度扫描的候选索引缺失");
        assertTrue(indexExists("correction_case", "uk_case_feedback"), "一条反馈只开一张工单");

        assertTrue(indexExists("wiki_page", "uk_wiki_page_kb_slug"), "V17 的 (kb_id, slug) 唯一键缺失");
        assertTrue(indexExists("wiki_revision", "uk_wiki_rev_page_no"), "V17 的 (page_id, revision_no) 唯一键缺失");
        assertTrue(indexExists("wiki_reminder", "uk_wiki_remind_bucket"), "V17 的提醒桶唯一键缺失");
        assertTrue(indexExists("wiki_source", "uk_wiki_source"), "重复挂接来源必须只留一行");
        assertTrue(indexExists("wiki_sync_task", "uk_wiki_task"), "一页一修订一操作只有一条任务");
        assertTrue(indexExists("wiki_review_assignment", "uk_wiki_assign"), "重复指派必须只留一行");
    }

    /** P2-W1 的 expand-only 接缝：引用与反馈要能指向 Wiki 页面，且不得破坏既有 Document 引用。 */
    @Test
    void citationAndFeedbackCarryWikiSeamsWithoutBreakingOldSemantics() throws SQLException {
        Flyway.configure().dataSource(URL, DDL_USER, DDL_PASSWORD)
                .locations("classpath:db/migration").load().migrate();
        for (String column : new String[]{"source_type", "page_id", "page_revision_no"}) {
            assertTrue(columnExists("citation", column), "citation 缺 Wiki 引用接缝列 " + column);
        }
        assertTrue(columnIsNullable("citation", "document_id"),
                "V16 必须把 document_id 放宽为可空，否则 Wiki 来源的引用无法落库");
        assertTrue(indexExists("citation", "idx_citation_page"),
                "引用预览按 (page_id, page_revision_no) 回查，缺索引就是全表扫");
        assertTrue(columnExists("chat_feedback", "triaged_case_id"), "反馈分诊缺分案列");
        assertTrue(columnExists("chat_feedback", "status"), "反馈分诊缺筛选位");
        // 冗余筛选位必须有默认值：既有分诊行不能因为新列变成 NULL 而筛不出来
        assertEquals("NEW", columnDefault("chat_feedback", "status"),
                "chat_feedback.status 默认值必须回填既有行为 NEW");
        assertEquals("DOCUMENT", columnDefault("citation", "source_type"),
                "citation.source_type 默认值必须回填既有行为 DOCUMENT");
    }

    @Test
    void appAccountCanDmlButCannotDdl() throws SQLException {
        Flyway.configure().dataSource(URL, DDL_USER, DDL_PASSWORD)
                .locations("classpath:db/migration").load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, APP_USER, APP_PASSWORD)) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM audit_event")) {
                assertTrue(rs.next());
            }
            String table = "ddl_probe_" + UUID.randomUUID().toString().replace("-", "");
            SQLException denied = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + table + " (id INT PRIMARY KEY)");
                }
            });
            assertTrue(denied.getErrorCode() == 1142 || denied.getErrorCode() == 1044
                            || denied.getMessage().toLowerCase().contains("denied"),
                    "DDL 未被拒绝: code=" + denied.getErrorCode() + " msg=" + denied.getMessage());
        }
    }

    private static Set<String> tables() throws SQLException {
        Set<String> out = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(URL, DDL_USER, DDL_PASSWORD);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1).toLowerCase());
                }
            }
        }
        return out;
    }

    private static boolean columnExists(String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, DDL_USER, DDL_PASSWORD);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** 唯一键与扫描索引都属于契约的一部分：代码里的 `ON DUPLICATE KEY` 依赖它们存在。 */
    private static boolean indexExists(String table, String index) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, DDL_USER, DDL_PASSWORD);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.statistics "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    private static boolean columnIsNullable(String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, DDL_USER, DDL_PASSWORD);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT is_nullable FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "YES".equalsIgnoreCase(rs.getString(1));
            }
        }
    }

    /** MySQL 8 的 COLUMN_DEFAULT 对字符串默认值可能带引号，比较前统一剥掉。 */
    private static String columnDefault(String table, String column) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, DDL_USER, DDL_PASSWORD);
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT column_default FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString(1) == null) {
                    return null;
                }
                String value = rs.getString(1).strip();
                return value.length() >= 2 && value.startsWith("'") && value.endsWith("'")
                        ? value.substring(1, value.length() - 1) : value;
            }
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
