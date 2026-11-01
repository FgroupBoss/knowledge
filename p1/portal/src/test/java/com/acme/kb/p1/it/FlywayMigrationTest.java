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
        assertTrue(applied.length >= 11, "至少应有 V1..V11 共 11 个已应用版本，实际 " + applied.length);
        assertEquals(0, flyway.info().pending().length, "迁移后不应有 pending 版本");

        Set<String> versions = new HashSet<>();
        for (MigrationInfo info : applied) {
            versions.add(info.getVersion().getVersion());
            assertNotNull(info.getChecksum(), "版本 " + info.getVersion() + " 必须已校验");
        }
        for (int i = 1; i <= 11; i++) {
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

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
