package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 入库管道写入。所有状态迁移都带前置状态条件（乐观状态机），
 * 迁移失败即 0 行，由服务层转成 INVALID_STATE_TRANSITION，避免并发双写有效索引。
 */
@Mapper
public interface PipelineCommandMapper {

    @Insert("""
            INSERT INTO document (id, tenant_id, workspace_id, kb_id, display_name, version, object_key,
                                  sha256, size_bytes, mime_type, extension, classification, status,
                                  engine_doc_id, acl_enforced, created_by, created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{workspaceId}, #{kbId}, #{displayName}, #{version}, #{objectKey},
                    #{sha256}, #{sizeBytes}, #{mimeType}, #{extension}, #{classification}, #{status},
                    NULL, 0, #{createdBy}, #{now}, #{now})
            """)
    int insertDocument(Map<String, Object> params);

    @Insert("""
            INSERT INTO ingest_task (id, tenant_id, document_id, document_ver, operation_id, task_type,
                                     status, attempts, max_attempts, next_run_at, correlation_id,
                                     created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{documentId}, #{documentVersion}, #{operationId}, #{taskType},
                    'PENDING', 0, #{maxAttempts}, #{nextRunAt}, #{correlationId}, #{now}, #{now})
            """)
    int insertTask(Map<String, Object> params);

    @Insert("""
            INSERT INTO outbox_event (id, tenant_id, event_id, aggregate_id, aggregate_type, event_type,
                                      topic, partition_key, schema_version, payload, headers, created_at)
            VALUES (#{id}, #{tenantId}, #{eventId}, #{aggregateId}, #{aggregateType}, #{eventType},
                    #{topic}, #{partitionKey}, 1, #{payload}, #{headers}, #{now})
            """)
    int insertOutbox(Map<String, Object> params);

    @Select("""
            SELECT id, tenant_id AS tenantId, event_id AS eventId, aggregate_id AS aggregateId,
                   aggregate_type AS aggregateType, event_type AS eventType, topic,
                   partition_key AS partitionKey, schema_version AS schemaVersion,
                   CAST(payload AS CHAR) AS payload, CAST(headers AS CHAR) AS headers,
                   created_at AS createdAt
            FROM outbox_event
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<Map<String, Object>> lockUnpublished(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event SET published_at = #{now}, attempts = attempts + 1
            WHERE id = #{id} AND published_at IS NULL
            """)
    int markPublished(@Param("id") String id, @Param("now") Instant now);

    @Update("""
            UPDATE outbox_event SET attempts = attempts + 1, last_error = #{error}
            WHERE id = #{id} AND published_at IS NULL
            """)
    int markPublishFailure(@Param("id") String id, @Param("error") String error);

    @Select("SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL")
    int unpublishedCount();

    @Select("""
            SELECT COALESCE(TIMESTAMPDIFF(SECOND, MIN(created_at), UTC_TIMESTAMP(3)), 0)
            FROM outbox_event WHERE published_at IS NULL
            """)
    long oldestUnpublishedAgeSeconds();

    /** 幂等认领：只有可认领状态、或租约已过期的进行中任务才能被抢到。 */
    @Update("""
            UPDATE ingest_task
            SET status = 'CLAIMED', lease_owner = #{owner}, lease_until = #{leaseUntil},
                attempts = attempts + 1, started_at = COALESCE(started_at, #{now}), updated_at = #{now}
            WHERE operation_id = #{operationId}
              AND (status IN ('PENDING','RETRY_SCHEDULED')
                   OR (status IN ('CLAIMED','RUNNING') AND (lease_until IS NULL OR lease_until < #{now}))
                   OR lease_owner = #{owner})
            """)
    int claimTask(@Param("operationId") String operationId, @Param("owner") String owner,
                  @Param("leaseUntil") Instant leaseUntil, @Param("now") Instant now);

    @Update("""
            UPDATE ingest_task SET status = 'RUNNING', updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner} AND status = 'CLAIMED'
            """)
    int markRunning(@Param("id") String id, @Param("owner") String owner, @Param("now") Instant now);

    @Update("""
            UPDATE ingest_task
            SET status = #{status}, error_code = #{errorCode}, error_summary = #{errorSummary},
                next_run_at = #{nextRunAt}, lease_owner = NULL, lease_until = NULL,
                finished_at = #{finishedAt}, updated_at = #{now}
            WHERE id = #{id} AND lease_owner = #{owner}
            """)
    int finishTask(@Param("id") String id, @Param("owner") String owner, @Param("status") String status,
                   @Param("errorCode") String errorCode, @Param("errorSummary") String errorSummary,
                   @Param("nextRunAt") Instant nextRunAt, @Param("finishedAt") Instant finishedAt,
                   @Param("now") Instant now);

    /** 不带租约约束的任务复位：用于 worker 崩溃后由下一次投递重新认领。 */
    @Update("""
            UPDATE ingest_task SET status = 'RETRY_SCHEDULED', next_run_at = #{nextRunAt},
                   lease_owner = NULL, lease_until = NULL, updated_at = #{now}
            WHERE operation_id = #{operationId} AND status IN ('CLAIMED','RUNNING')
              AND (lease_until IS NULL OR lease_until < #{now})
            """)
    int reclaimExpiredTask(@Param("operationId") String operationId, @Param("nextRunAt") Instant nextRunAt,
                           @Param("now") Instant now);

    @Update("""
            UPDATE document SET status = #{toStatus}, updated_at = #{now}
              , engine_doc_id = COALESCE(#{engineDocId}, engine_doc_id)
              , error_code = #{errorCode}, error_summary = #{errorSummary}
            WHERE id = #{documentId} AND status = #{fromStatus}
            """)
    int transitionDocument(@Param("documentId") String documentId, @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus, @Param("engineDocId") String engineDocId,
                           @Param("errorCode") String errorCode, @Param("errorSummary") String errorSummary,
                           @Param("now") Instant now);

    @Insert("""
            INSERT INTO event_consumption (event_id, topic, operation_id, partition_no, consumer_offset,
                                          outcome, first_seen_at, last_seen_at, seen_count)
            VALUES (#{eventId}, #{topic}, #{operationId}, #{partition}, #{offset}, #{outcome},
                    #{now}, #{now}, 1)
            ON DUPLICATE KEY UPDATE seen_count = seen_count + 1, last_seen_at = VALUES(last_seen_at)
            """)
    int recordConsumption(@Param("eventId") String eventId, @Param("topic") String topic,
                          @Param("operationId") String operationId, @Param("partition") int partition,
                          @Param("offset") long offset, @Param("outcome") String outcome,
                          @Param("now") Instant now);

    @Update("""
            UPDATE event_consumption SET outcome = #{outcome}, last_seen_at = #{now}
            WHERE event_id = #{eventId}
            """)
    int updateConsumptionOutcome(@Param("eventId") String eventId, @Param("outcome") String outcome,
                                 @Param("now") Instant now);

    @Select("SELECT outcome, seen_count AS seenCount FROM event_consumption WHERE event_id = #{eventId}")
    Map<String, Object> consumptionOf(@Param("eventId") String eventId);

    @Insert("""
            INSERT INTO idempotency_record (id, tenant_id, actor_id, scope, idempotency_key, resource_id,
                                           request_digest, created_at)
            VALUES (#{id}, #{tenantId}, #{actorId}, #{scope}, #{key}, #{resourceId}, #{digest}, #{now})
            """)
    int insertIdempotency(@Param("id") String id, @Param("tenantId") String tenantId,
                          @Param("actorId") String actorId, @Param("scope") String scope,
                          @Param("key") String key, @Param("resourceId") String resourceId,
                          @Param("digest") String digest, @Param("now") Instant now);

    @Select("""
            SELECT resource_id FROM idempotency_record
            WHERE tenant_id = #{tenantId} AND actor_id = #{actorId} AND scope = #{scope}
              AND idempotency_key = #{key}
            """)
    String findIdempotentResource(@Param("tenantId") String tenantId, @Param("actorId") String actorId,
                                  @Param("scope") String scope, @Param("key") String key);

    @Select("""
            SELECT request_digest FROM idempotency_record
            WHERE tenant_id = #{tenantId} AND actor_id = #{actorId} AND scope = #{scope}
              AND idempotency_key = #{key}
            """)
    String findIdempotentDigest(@Param("tenantId") String tenantId, @Param("actorId") String actorId,
                                @Param("scope") String scope, @Param("key") String key);

    @Select("""
            SELECT status, COUNT(*) AS total FROM ingest_task GROUP BY status
            """)
    List<Map<String, Object>> taskStatusCounts();
}
