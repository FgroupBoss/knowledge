package com.acme.kb.p0.repo;

import com.acme.kb.p0.common.Classification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeBaseMapper {

    record Row(String id, String tenantId, String workspaceId, String code, String name,
               String engine, String engineKbId, Classification classification, long aclVersion,
               String status) {
    }

    @Select("""
            SELECT id, tenant_id, workspace_id, code, name, engine, engine_kb_id,
                   classification, acl_version, status
            FROM knowledge_base WHERE id = #{id}
            """)
    Row findById(@Param("id") String id);

    @Select("""
            SELECT kb.id, kb.tenant_id, kb.workspace_id, kb.code, kb.name, kb.engine, kb.engine_kb_id,
                   kb.classification, kb.acl_version, kb.status
            FROM knowledge_base kb
            JOIN workspace_member m ON m.workspace_id = kb.workspace_id
            WHERE kb.tenant_id = #{tenantId} AND m.user_id = #{userId} AND kb.status = 'ACTIVE'
            """)
    List<Row> listReadableForUser(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT id, tenant_id, workspace_id, code, name, engine, engine_kb_id,
                   classification, acl_version, status
            FROM knowledge_base WHERE tenant_id = #{tenantId} AND workspace_id = #{workspaceId} AND code = #{code}
            """)
    Row findByCode(@Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId,
                   @Param("code") String code);

    @Select("SELECT acl_version FROM knowledge_base WHERE id = #{id} AND tenant_id = #{tenantId}")
    Long aclVersion(@Param("id") String id, @Param("tenantId") String tenantId);

    /** 授权边界变化时递增，缓存键携带该版本，撤权即时生效。 */
    @Update("""
            UPDATE knowledge_base SET acl_version = acl_version + 1, updated_at = UTC_TIMESTAMP(3)
            WHERE workspace_id = #{workspaceId}
            """)
    int bumpAclVersionOfWorkspace(@Param("workspaceId") String workspaceId);

    @Update("""
            UPDATE knowledge_base SET acl_version = acl_version + 1, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int bumpAclVersionOfKb(@Param("id") String id);

    @Select("""
            SELECT id, tenant_id, workspace_id, code, name, engine, engine_kb_id,
                   classification, acl_version, status
            FROM knowledge_base WHERE workspace_id = #{workspaceId}
            """)
    List<Row> listByWorkspace(@Param("workspaceId") String workspaceId);

    @Select("""
            SELECT kb.id, kb.tenant_id, kb.workspace_id, kb.code, kb.name, kb.engine, kb.engine_kb_id,
                   kb.classification, kb.acl_version, kb.status
            FROM knowledge_base kb
            WHERE kb.tenant_id = #{tenantId} AND kb.status = 'ACTIVE'
            ORDER BY kb.workspace_id, kb.code
            """)
    List<Row> listForTenant(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE knowledge_base SET engine_kb_id = #{engineKbId}, updated_at = UTC_TIMESTAMP(3)
            WHERE id = #{id} AND tenant_id = #{tenantId}
            """)
    int linkEngineKb(@Param("id") String id, @Param("tenantId") String tenantId,
                     @Param("engineKbId") String engineKbId);
}
