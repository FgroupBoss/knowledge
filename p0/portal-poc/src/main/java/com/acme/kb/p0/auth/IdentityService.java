package com.acme.kb.p0.auth;

import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.repo.TenantMapper;
import com.acme.kb.p0.repo.UserMapper;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 身份映射：数据库是身份与成员关系的唯一事实源。
 * 真实部署走 OIDC（issuer+subject 唯一键），P0 用受控测试身份，但映射与授权代码路径完全相同。
 */
@Service
public class IdentityService {

    public static final String TEST_ISSUER = "p0-test";
    private static final Pattern SUBJECT = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}:[a-z0-9][a-z0-9._-]{1,63}$");

    private final UserMapper users;
    private final TenantMapper tenants;

    public IdentityService(UserMapper users, TenantMapper tenants) {
        this.users = users;
        this.tenants = tenants;
    }

    public Actor resolve(String issuer, String subject) {
        UserMapper.Row row = users.findByIssuerSubject(issuer, subject);
        if (row == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "身份未映射");
        }
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "身份已停用");
        }
        TenantMapper.Row tenant = tenants.findById(row.tenantId());
        if (tenant == null || !"ACTIVE".equals(tenant.status())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "租户不可用");
        }
        return new Actor(row.id(), row.tenantId(), tenant.code(), row.subject(), row.displayName(),
                row.clearance());
    }

    /** P0 测试身份：{@code tenantCode:userCode}，仍然只从 MySQL 取授权事实。 */
    public Actor resolveTestIdentity(String headerValue) {
        if (headerValue == null || !SUBJECT.matcher(headerValue).matches()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "测试身份格式不合法");
        }
        String[] parts = headerValue.split(":", 2);
        UserMapper.Row row = users.findByTenantAndCode(parts[0], parts[1]);
        if (row == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "身份未映射");
        }
        if (!TEST_ISSUER.equals(row.issuer())) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "身份域不匹配");
        }
        return resolve(row.issuer(), row.subject());
    }
}
