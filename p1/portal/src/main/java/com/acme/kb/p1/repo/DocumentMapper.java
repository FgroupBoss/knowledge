package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DocumentMapper {

    record Row(
            String id, String tenantId, String workspaceId, String kbId, String displayName,
            int version, String objectKey, String sha256, long sizeBytes, String mimeType,
            String extension, String classification, String status, String engineDocId,
            boolean aclEnforced, String errorCode, String errorSummary, String createdBy,
            Instant createdAt, Instant updatedAt) {
    }

    @Select("""
            SELECT id, tenant_id, workspace_id, kb_id, display_name, version, object_key, sha256,
                   size_bytes, mime_type, extension, classification, status, engine_doc_id,
                   acl_enforced, error_code, error_summary, created_by, created_at, updated_at
            FROM document WHERE id = #{id}
            """)
    Row findById(@Param("id") String id);

    @Select("""
            SELECT id, tenant_id, workspace_id, kb_id, display_name, version, object_key, sha256,
                   size_bytes, mime_type, extension, classification, status, engine_doc_id,
                   acl_enforced, error_code, error_summary, created_by, created_at, updated_at
            FROM document WHERE kb_id = #{kbId} AND sha256 = #{sha256} ORDER BY version DESC LIMIT 1
            """)
    Row findLatestByHash(@Param("kbId") String kbId, @Param("sha256") String sha256);

    @Select("SELECT COUNT(*) FROM document WHERE kb_id = #{kbId}")
    int countInKb(@Param("kbId") String kbId);

    @Select("""
            SELECT id, tenant_id, workspace_id, kb_id, display_name, version, object_key, sha256,
                   size_bytes, mime_type, extension, classification, status, engine_doc_id,
                   acl_enforced, error_code, error_summary, created_by, created_at, updated_at
            FROM document WHERE kb_id = #{kbId} AND status = 'READY'
            """)
    List<Row> listReadyInKb(@Param("kbId") String kbId);

    @Select("""
            SELECT d.id, d.tenant_id, d.workspace_id, d.kb_id, d.display_name, d.version, d.object_key,
                   d.sha256, d.size_bytes, d.mime_type, d.extension, d.classification, d.status,
                   d.engine_doc_id, d.acl_enforced, d.error_code, d.error_summary, d.created_by,
                   d.created_at, d.updated_at
            FROM document d
            WHERE d.engine_doc_id = #{engineDocId} AND d.status = 'READY'
            """)
    List<Row> findByEngineDoc(@Param("engineDocId") String engineDocId);

    @Select("SELECT COUNT(*) FROM document WHERE status IN ('DELETING','COMPENSATION_REQUIRED')")
    int countUnconverged();
}
