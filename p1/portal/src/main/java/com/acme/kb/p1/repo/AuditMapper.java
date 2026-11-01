package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface AuditMapper {

    record Row(String id, long seq, String tenantId, String actorId, String action, String resourceType,
               String resourceId, String result, String reasonCode, String requestId, String traceId,
               String sourceIp, String metadataJson, Instant createdAt, String prevHash,
               String recordHash) {
    }

    @Insert("""
            INSERT INTO audit_event
              (id, seq, tenant_id, actor_id, action, resource_type, resource_id, result,
               reason_code, request_id, trace_id, source_ip, metadata_json, created_at,
               prev_hash, record_hash)
            VALUES
              (#{id}, #{seq}, #{tenantId}, #{actorId}, #{action}, #{resourceType}, #{resourceId}, #{result},
               #{reasonCode}, #{requestId}, #{traceId}, #{sourceIp}, #{metadataJson}, #{createdAt},
               #{prevHash}, #{recordHash})
            """)
    int insert(@Param("id") String id, @Param("seq") Long seq, @Param("tenantId") String tenantId,
               @Param("actorId") String actorId, @Param("action") String action,
               @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
               @Param("result") String result, @Param("reasonCode") String reasonCode,
               @Param("requestId") String requestId, @Param("traceId") String traceId,
               @Param("sourceIp") String sourceIp, @Param("metadataJson") String metadataJson,
               @Param("createdAt") Instant createdAt, @Param("prevHash") String prevHash,
               @Param("recordHash") String recordHash);

    /** 建链头（幂等）。并发首写由唯一键串行化。 */
    @Insert("""
            INSERT INTO audit_head (tenant_id, last_seq, last_hash, updated_at)
            VALUES (#{tenantId}, 0, NULL, #{now})
            ON DUPLICATE KEY UPDATE tenant_id = tenant_id
            """)
    int ensureHead(@Param("tenantId") String tenantId, @Param("now") Instant now);

    @Select("""
            SELECT last_seq AS lastSeq, last_hash AS lastHash
            FROM audit_head WHERE tenant_id = #{tenantId} FOR UPDATE
            """)
    Map<String, Object> lockHead(@Param("tenantId") String tenantId);

    @Update("""
            UPDATE audit_head SET last_seq = #{seq}, last_hash = #{hash}, updated_at = #{now}
            WHERE tenant_id = #{tenantId}
            """)
    int updateHead(@Param("tenantId") String tenantId, @Param("seq") long seq,
                   @Param("hash") String hash, @Param("now") Instant now);

    @Select("""
            SELECT id, seq, tenant_id, actor_id, action, resource_type, resource_id, result, reason_code,
                   request_id, trace_id, source_ip, metadata_json, created_at, prev_hash, record_hash
            FROM audit_event WHERE tenant_id = #{tenantId} AND seq IS NOT NULL
            ORDER BY seq
            """)
    List<Row> chain(@Param("tenantId") String tenantId);

    @Select("""
            SELECT id, seq, tenant_id, actor_id, action, resource_type, resource_id, result, reason_code,
                   request_id, trace_id, source_ip, metadata_json, created_at
            FROM audit_event WHERE trace_id = #{traceId} ORDER BY created_at
            """)
    List<Row> findByTrace(@Param("traceId") String traceId);

    @Select("""
            SELECT action, result, COUNT(*) AS total FROM audit_event
            WHERE tenant_id = #{tenantId} AND created_at >= #{since}
            GROUP BY action, result
            """)
    List<Map<String, Object>> summary(@Param("tenantId") String tenantId, @Param("since") Instant since);

    /**
     * 审计外发：与 audit_event 同事务写入 outbox，由发布器投递 kb.audit.v1。
     * Kafka 不可用只影响外发延迟，不影响在线审计落库。
     */
    @Insert("""
            INSERT INTO outbox_event (id, tenant_id, event_id, aggregate_id, aggregate_type, event_type,
                                      topic, partition_key, schema_version, payload, headers, created_at)
            VALUES (#{id}, #{tenantId}, #{eventId}, #{aggregateId}, 'audit', 'audit.recorded',
                    #{topic}, #{partitionKey}, 1, #{payload}, NULL, #{now})
            """)
    int insertOutbox(@Param("id") String id, @Param("tenantId") String tenantId,
                     @Param("eventId") String eventId, @Param("aggregateId") String aggregateId,
                     @Param("topic") String topic, @Param("partitionKey") String partitionKey,
                     @Param("payload") String payload, @Param("now") Instant now);
}
