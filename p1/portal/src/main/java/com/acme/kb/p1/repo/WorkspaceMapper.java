package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WorkspaceMapper {

    record Row(String id, String tenantId, String code, String name, String ownerUserId, String status) {
    }

    @Select("SELECT id, tenant_id, code, name, owner_user_id, status FROM workspace WHERE id = #{id}")
    Row findById(@Param("id") String id);

    @Select("""
            SELECT w.id, w.tenant_id, w.code, w.name, w.owner_user_id, w.status
            FROM workspace w JOIN workspace_member m ON m.workspace_id = w.id
            WHERE w.tenant_id = #{tenantId} AND m.user_id = #{userId} AND w.status = 'ACTIVE'
            ORDER BY w.code
            """)
    List<Row> listForUser(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("SELECT id, tenant_id, code, name, owner_user_id, status FROM workspace WHERE tenant_id = #{tenantId} AND code = #{code}")
    Row findByCode(@Param("tenantId") String tenantId, @Param("code") String code);
}
