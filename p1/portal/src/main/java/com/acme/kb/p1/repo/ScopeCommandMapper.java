package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;

/** 授权边界写入：每次成员/ACL 变更都要递增 acl_version，配合缓存水位即时失效。 */
@Mapper
public interface ScopeCommandMapper {

    @Insert("""
            INSERT INTO tenant (id, code, name, status, created_at, updated_at)
            VALUES (#{id}, #{code}, #{name}, 'ACTIVE', #{now}, #{now})
            """)
    int insertTenant(@Param("id") String id, @Param("code") String code, @Param("name") String name,
                     @Param("now") Instant now);

    @Insert("""
            INSERT INTO app_user (id, tenant_id, issuer, subject, display_name, clearance, status,
                                  created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{issuer}, #{subject}, #{displayName}, #{clearance}, 'ACTIVE',
                    #{now}, #{now})
            """)
    int insertUser(@Param("id") String id, @Param("tenantId") String tenantId, @Param("issuer") String issuer,
                   @Param("subject") String subject, @Param("displayName") String displayName,
                   @Param("clearance") String clearance, @Param("now") Instant now);

    @Insert("""
            INSERT INTO workspace (id, tenant_id, code, name, owner_user_id, status, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{code}, #{name}, #{ownerUserId}, 'ACTIVE', #{now}, #{now})
            """)
    int insertWorkspace(@Param("id") String id, @Param("tenantId") String tenantId, @Param("code") String code,
                        @Param("name") String name, @Param("ownerUserId") String ownerUserId,
                        @Param("now") Instant now);

    @Insert("""
            INSERT INTO workspace_member (id, workspace_id, user_id, role, created_at, updated_at)
            VALUES (#{id}, #{workspaceId}, #{userId}, #{role}, #{now}, #{now})
            ON DUPLICATE KEY UPDATE role = VALUES(role), updated_at = VALUES(updated_at)
            """)
    int upsertMember(@Param("id") String id, @Param("workspaceId") String workspaceId,
                     @Param("userId") String userId, @Param("role") String role, @Param("now") Instant now);

    @Update("DELETE FROM workspace_member WHERE workspace_id = #{workspaceId} AND user_id = #{userId}")
    int deleteMember(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Insert("""
            INSERT INTO knowledge_base (id, tenant_id, workspace_id, code, name, engine, engine_kb_id,
                                        classification, acl_version, status, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{workspaceId}, #{code}, #{name}, #{engine}, NULL,
                    #{classification}, 1, 'ACTIVE', #{now}, #{now})
            """)
    int insertKnowledgeBase(@Param("id") String id, @Param("tenantId") String tenantId,
                           @Param("workspaceId") String workspaceId, @Param("code") String code,
                           @Param("name") String name, @Param("engine") String engine,
                           @Param("classification") String classification, @Param("now") Instant now);

    @Insert("""
            INSERT INTO document_acl (id, tenant_id, document_id, subject_type, subject_id, grant_type, created_at)
            VALUES (#{id}, #{tenantId}, #{documentId}, 'USER', #{userId}, #{grant}, #{now})
            ON DUPLICATE KEY UPDATE created_at = VALUES(created_at)
            """)
    int upsertDocumentAcl(@Param("id") String id, @Param("tenantId") String tenantId,
                          @Param("documentId") String documentId, @Param("userId") String userId,
                          @Param("grant") String grant, @Param("now") Instant now);

    @Update("UPDATE document SET acl_enforced = 1, updated_at = #{now} WHERE id = #{documentId}")
    int enforceDocumentAcl(@Param("documentId") String documentId, @Param("now") Instant now);

    @Select("""
            SELECT u.id FROM app_user u JOIN tenant t ON t.id = u.tenant_id
            WHERE t.code = #{tenantCode} AND u.subject = #{userCode}
            """)
    String userIdByCode(@Param("tenantCode") String tenantCode, @Param("userCode") String userCode);

    @Select("""
            SELECT m.user_id FROM workspace_member m
            JOIN knowledge_base kb ON kb.workspace_id = m.workspace_id
            WHERE kb.id = #{kbId}
            """)
    java.util.List<String> memberUserIdsOfKb(@Param("kbId") String kbId);
}
