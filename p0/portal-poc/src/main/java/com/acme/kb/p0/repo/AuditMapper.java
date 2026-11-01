package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {

    record Row(String id, String tenantId, String actorId, String action, String resourceType,
               String resourceId, String result, String reasonCode, String requestId, String traceId,
               String sourceIp, Instant createdAt) {
    }

    @Insert("""
            INSERT INTO audit_event
              (id, tenant_id, actor_id, action, resource_type, resource_id, result,
               reason_code, request_id, trace_id, source_ip, metadata_json, created_at)
            VALUES
              (#{id}, #{tenantId}, #{actorId}, #{action}, #{resourceType}, #{resourceId}, #{result},
               #{reasonCode}, #{requestId}, #{traceId}, #{sourceIp}, #{metadataJson}, #{createdAt})
            """)
    int insert(@Param("id") String id, @Param("tenantId") String tenantId, @Param("actorId") String actorId,
               @Param("action") String action, @Param("resourceType") String resourceType,
               @Param("resourceId") String resourceId, @Param("result") String result,
               @Param("reasonCode") String reasonCode, @Param("requestId") String requestId,
               @Param("traceId") String traceId, @Param("sourceIp") String sourceIp,
               @Param("metadataJson") String metadataJson, @Param("createdAt") Instant createdAt);

    @Select("""
            SELECT id, tenant_id, actor_id, action, resource_type, resource_id, result, reason_code,
                   request_id, trace_id, source_ip, created_at
            FROM audit_event WHERE trace_id = #{traceId} ORDER BY created_at
            """)
    List<Row> findByTrace(@Param("traceId") String traceId);

    @Select("""
            SELECT action, result, COUNT(*) AS total FROM audit_event
            WHERE tenant_id = #{tenantId} AND created_at >= #{since}
            GROUP BY action, result
            """)
    List<Map<String, Object>> summary(@Param("tenantId") String tenantId, @Param("since") Instant since);
}
