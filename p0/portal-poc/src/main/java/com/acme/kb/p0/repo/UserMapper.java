package com.acme.kb.p0.repo;

import com.acme.kb.p0.common.Classification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    record Row(String id, String tenantId, String issuer, String subject, String displayName,
               Classification clearance, String status) {
    }

    @Select("""
            SELECT id, tenant_id, issuer, subject, display_name, clearance, status
            FROM app_user WHERE issuer = #{issuer} AND subject = #{subject}
            """)
    Row findByIssuerSubject(@Param("issuer") String issuer, @Param("subject") String subject);

    @Select("""
            SELECT u.id, u.tenant_id, u.issuer, u.subject, u.display_name, u.clearance, u.status
            FROM app_user u JOIN tenant t ON t.id = u.tenant_id
            WHERE t.code = #{tenantCode} AND u.subject = #{userCode}
            """)
    Row findByTenantAndCode(@Param("tenantCode") String tenantCode, @Param("userCode") String userCode);

    @Select("SELECT id, tenant_id, issuer, subject, display_name, clearance, status FROM app_user WHERE id = #{id}")
    Row findById(@Param("id") String id);

    @Select("""
            SELECT clearance FROM app_user WHERE id = #{id} AND tenant_id = #{tenantId} AND status = 'ACTIVE'
            """)
    Classification clearanceOf(@Param("id") String id, @Param("tenantId") String tenantId);

    @Select("""
            SELECT u.id FROM app_user u JOIN tenant t ON t.id = u.tenant_id
            WHERE t.code = #{tenantCode} AND u.subject = #{userCode} AND u.status = 'ACTIVE'
            """)
    String idByTenantAndCode(@Param("tenantCode") String tenantCode, @Param("userCode") String userCode);
}
