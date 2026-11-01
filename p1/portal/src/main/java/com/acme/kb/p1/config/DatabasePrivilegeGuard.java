package com.acme.kb.p1.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * #6 启动校验：运行账号只能有 SELECT/INSERT/UPDATE/DELETE。
 * 发现 CREATE/DROP/ALTER/GRANT/ALL PRIVILEGES 等权限时拒绝启动——
 * 否则“用 DDL 账号跑应用”的配置错误会一直潜伏到生产。
 */
@Component
public class DatabasePrivilegeGuard {

    private static final Logger log = LoggerFactory.getLogger(DatabasePrivilegeGuard.class);
    private static final List<String> FORBIDDEN = List.of(
            "ALL PRIVILEGES", "CREATE", "DROP", "ALTER", "GRANT OPTION", "INDEX", "TRIGGER", "REFERENCES");

    private final JdbcTemplate jdbc;
    private final boolean enforced;

    public DatabasePrivilegeGuard(JdbcTemplate jdbc, KbProperties props) {
        this.jdbc = jdbc;
        this.enforced = props.database().enforceLeastPrivilege();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (!enforced) {
            log.warn("db least-privilege check disabled：仅限本地联调，试点必须为 true");
            return;
        }
        List<String> grants = new ArrayList<>();
        try {
            jdbc.query("SHOW GRANTS FOR CURRENT_USER()", rs -> {
                grants.add(rs.getString(1));
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法读取当前数据库账号权限，拒绝启动", e);
        }
        List<String> violations = new ArrayList<>();
        for (String grant : grants) {
            String upper = grant.toUpperCase(Locale.ROOT);
            for (String privilege : FORBIDDEN) {
                if (upper.contains(privilege)) {
                    violations.add(grant);
                    break;
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("数据库运行账号权限过宽（#6 账号拆分）："
                    + violations + "；应用账号只允许 SELECT/INSERT/UPDATE/DELETE");
        }
        log.info("db least-privilege check passed grants={}", grants.size());
    }
}
