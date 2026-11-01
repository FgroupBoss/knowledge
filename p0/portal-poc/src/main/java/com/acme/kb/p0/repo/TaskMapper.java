package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** 任务读取侧（列显式别名为驼峰，避免依赖自动映射的隐式行为）。 */
@Mapper
public interface TaskMapper {

    record TaskRow(String id, String tenantId, String documentId, int documentVer, String operationId,
                   String taskType, String status, int attempts, int maxAttempts, Instant nextRunAt,
                   String leaseOwner, Instant leaseUntil, String errorCode, String errorSummary,
                   String correlationId, Instant startedAt, Instant finishedAt, Instant createdAt,
                   Instant updatedAt) {
    }

    String COLUMNS = "id, tenant_id AS tenantId, document_id AS documentId, document_ver AS documentVer, "
            + "operation_id AS operationId, task_type AS taskType, status, attempts, max_attempts AS maxAttempts, "
            + "next_run_at AS nextRunAt, lease_owner AS leaseOwner, lease_until AS leaseUntil, "
            + "error_code AS errorCode, error_summary AS errorSummary, correlation_id AS correlationId, "
            + "started_at AS startedAt, finished_at AS finishedAt, created_at AS createdAt, "
            + "updated_at AS updatedAt";

    @Select("SELECT " + COLUMNS + " FROM ingest_task WHERE id = #{id}")
    TaskRow findById(@Param("id") String id);

    @Select("SELECT " + COLUMNS + " FROM ingest_task WHERE operation_id = #{operationId}")
    TaskRow findByOperation(@Param("operationId") String operationId);

    @Select("SELECT " + COLUMNS + " FROM ingest_task WHERE document_id = #{documentId} "
            + "ORDER BY created_at DESC LIMIT 1")
    TaskRow latestForDocument(@Param("documentId") String documentId);

    @Select("SELECT " + COLUMNS + " FROM ingest_task WHERE document_id = #{documentId} ORDER BY created_at")
    List<TaskRow> listForDocument(@Param("documentId") String documentId);

    @Select("""
            SELECT document_id FROM ingest_task WHERE id = #{id}
            """)
    String documentIdOfTask(@Param("id") String id);
}
