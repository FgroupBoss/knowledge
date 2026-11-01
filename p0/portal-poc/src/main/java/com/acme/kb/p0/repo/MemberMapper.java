package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemberMapper {

    record Row(String id, String workspaceId, String userId, String role) {
    }

    @Select("SELECT id, workspace_id, user_id, role FROM workspace_member WHERE workspace_id = #{wsId} AND user_id = #{userId}")
    Row find(@Param("wsId") String wsId, @Param("userId") String userId);

    @Select("SELECT id, workspace_id, user_id, role FROM workspace_member WHERE user_id = #{userId}")
    List<Row> listForUser(@Param("userId") String userId);

    @Select("""
            SELECT m.id, m.workspace_id, m.user_id, m.role
            FROM workspace_member m JOIN workspace w ON w.id = m.workspace_id
            WHERE w.tenant_id = #{tenantId} AND m.user_id = #{userId}
            """)
    List<Row> listForUserInTenant(@Param("tenantId") String tenantId, @Param("userId") String userId);

    @Select("""
            SELECT m.id, m.workspace_id, m.user_id, m.role FROM workspace_member m
            JOIN workspace w ON w.id = m.workspace_id
            WHERE m.workspace_id = #{wsId} AND w.tenant_id = #{tenantId}
            """)
    List<Row> listMembers(@Param("wsId") String wsId, @Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM workspace_member WHERE workspace_id = #{wsId} AND user_id = #{userId}")
    int countOf(@Param("wsId") String wsId, @Param("userId") String userId);
}
