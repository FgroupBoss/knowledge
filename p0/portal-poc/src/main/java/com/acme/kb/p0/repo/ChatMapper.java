package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ChatMapper {

    record RequestRow(String id, String tenantId, String workspaceId, String actorId, String kbId,
                      String conversationId, long aclVersion, String idempotencyKey, String status,
                      String question, String answer, String engineModel, int promptChunks,
                      int engineCandidates, int droppedByAcl, int droppedByClearance, int droppedByStale,
                      long authorizationMs, long retrievalMs, Long firstTokenMs, long totalMs,
                      Integer promptTokens, Integer completionTokens, String errorCode, String traceId,
                      Instant createdAt, Instant completedAt) {
    }

    record CitationRow(String id, String tenantId, String requestId, String documentId, int documentVersion,
                       String kbId, String engineDocId, String engineChunkId, String title, Integer pageNo,
                       String section, Integer charStart, Integer charEnd, String snippet,
                       String snippetDigest, double score, boolean aclVerified, Instant createdAt) {
    }

    @Insert("""
            INSERT INTO chat_request (id, tenant_id, workspace_id, actor_id, kb_id, conversation_id,
                                      acl_version, idempotency_key, status, question, trace_id, created_at)
            VALUES (#{id}, #{tenantId}, #{workspaceId}, #{actorId}, #{kbId}, #{conversationId},
                    #{aclVersion}, #{idempotencyKey}, 'RUNNING', #{question}, #{traceId}, #{now})
            """)
    int insertRequest(@Param("id") String id, @Param("tenantId") String tenantId,
                      @Param("workspaceId") String workspaceId, @Param("actorId") String actorId,
                      @Param("kbId") String kbId, @Param("conversationId") String conversationId,
                      @Param("aclVersion") long aclVersion, @Param("idempotencyKey") String idempotencyKey,
                      @Param("question") String question, @Param("traceId") String traceId,
                      @Param("now") Instant now);

    @Update("""
            UPDATE chat_request SET status = #{status}, answer = #{answer}, engine_model = #{engineModel},
                   prompt_chunks = #{promptChunks}, engine_candidates = #{engineCandidates},
                   dropped_by_acl = #{droppedByAcl}, dropped_by_clearance = #{droppedByClearance},
                   dropped_by_stale = #{droppedByStale}, authorization_ms = #{authorizationMs},
                   retrieval_ms = #{retrievalMs}, first_token_ms = #{firstTokenMs}, total_ms = #{totalMs},
                   prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens},
                   error_code = #{errorCode}, completed_at = #{now}
            WHERE id = #{id}
            """)
    int completeRequest(@Param("id") String id, @Param("status") String status,
                        @Param("answer") String answer, @Param("engineModel") String engineModel,
                        @Param("promptChunks") int promptChunks, @Param("engineCandidates") int engineCandidates,
                        @Param("droppedByAcl") int droppedByAcl, @Param("droppedByClearance") int droppedByClearance,
                        @Param("droppedByStale") int droppedByStale, @Param("authorizationMs") long authorizationMs,
                        @Param("retrievalMs") long retrievalMs, @Param("firstTokenMs") Long firstTokenMs,
                        @Param("totalMs") long totalMs, @Param("promptTokens") Integer promptTokens,
                        @Param("completionTokens") Integer completionTokens, @Param("errorCode") String errorCode,
                        @Param("now") Instant now);

    @Select("""
            SELECT id, tenant_id, workspace_id, actor_id, kb_id, conversation_id, acl_version,
                   idempotency_key, status, question, answer, engine_model, prompt_chunks,
                   engine_candidates, dropped_by_acl, dropped_by_clearance, dropped_by_stale,
                   authorization_ms, retrieval_ms, first_token_ms, total_ms, prompt_tokens,
                   completion_tokens, error_code, trace_id, created_at, completed_at
            FROM chat_request WHERE id = #{id}
            """)
    RequestRow findRequest(@Param("id") String id);

    @Select("""
            SELECT id FROM chat_request WHERE tenant_id = #{tenantId} AND actor_id = #{actorId}
              AND idempotency_key = #{key}
            """)
    String findRequestIdByIdempotency(@Param("tenantId") String tenantId, @Param("actorId") String actorId,
                                      @Param("key") String key);

    @Insert("""
            INSERT INTO citation (id, tenant_id, request_id, document_id, document_version, kb_id,
                                  engine_doc_id, engine_chunk_id, title, page_no, section, char_start,
                                  char_end, snippet, snippet_digest, score, acl_verified, created_at)
            VALUES (#{id}, #{tenantId}, #{requestId}, #{documentId}, #{documentVersion}, #{kbId},
                    #{engineDocId}, #{engineChunkId}, #{title}, #{pageNo}, #{section}, #{charStart},
                    #{charEnd}, #{snippet}, #{snippetDigest}, #{score}, 1, #{now})
            """)
    int insertCitation(@Param("id") String id, @Param("tenantId") String tenantId,
                       @Param("requestId") String requestId, @Param("documentId") String documentId,
                       @Param("documentVersion") int documentVersion, @Param("kbId") String kbId,
                       @Param("engineDocId") String engineDocId, @Param("engineChunkId") String engineChunkId,
                       @Param("title") String title, @Param("pageNo") Integer pageNo,
                       @Param("section") String section, @Param("charStart") Integer charStart,
                       @Param("charEnd") Integer charEnd, @Param("snippet") String snippet,
                       @Param("snippetDigest") String snippetDigest, @Param("score") double score,
                       @Param("now") Instant now);

    @Select("""
            SELECT id, tenant_id, request_id, document_id, document_version, kb_id, engine_doc_id,
                   engine_chunk_id, title, page_no, section, char_start, char_end, snippet,
                   snippet_digest, score, acl_verified, created_at
            FROM citation WHERE id = #{id}
            """)
    CitationRow findCitation(@Param("id") String id);

    @Select("""
            SELECT id, tenant_id, request_id, document_id, document_version, kb_id, engine_doc_id,
                   engine_chunk_id, title, page_no, section, char_start, char_end, snippet,
                   snippet_digest, score, acl_verified, created_at
            FROM citation WHERE request_id = #{requestId} ORDER BY score DESC
            """)
    List<CitationRow> listByRequest(@Param("requestId") String requestId);

    @Select("SELECT COUNT(*) FROM citation")
    long countCitations();
}
