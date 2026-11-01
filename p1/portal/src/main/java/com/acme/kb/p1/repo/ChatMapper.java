package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ChatMapper {

    record RequestRow(String id, String messageId, String tenantId, String workspaceId, String actorId,
                      String kbId, String conversationId, long aclVersion, String idempotencyKey,
                      String status, String refusalReason, String question, String answer,
                      String engineModel, int promptChunks, int engineCandidates, int droppedByAcl,
                      int droppedByClearance, int droppedByStale, int droppedByUnsupportedCitation,
                      long authorizationMs, long retrievalMs, Long firstTokenMs, long totalMs,
                      Integer promptTokens, Integer completionTokens, String errorCode, String traceId,
                      Instant createdAt, Instant completedAt) {
    }

    record CitationRow(String id, String messageId, int citationNo, String tenantId, String requestId,
                       String documentId, int documentVersion, String kbId, String engineDocId,
                       String engineChunkId, String title, Integer pageNo, String section,
                       Integer charStart, Integer charEnd, String snippet, String snippetDigest,
                       String contentDigest, String sourceLocator, double score, long aclVersion,
                       boolean aclVerified, Instant createdAt) {
    }

    record ConversationRow(String id, String tenantId, String workspaceId, String userId, String title,
                           String status, Instant createdAt, Instant updatedAt) {
    }

    record MessageRow(String id, String tenantId, String conversationId, String requestId, String role,
                      String content, String status, Instant createdAt) {
    }

    @Insert("""
            INSERT IGNORE INTO conversation (id, tenant_id, workspace_id, user_id, title, status,
                                             created_at, updated_at)
            VALUES (#{id}, #{tenantId}, #{workspaceId}, #{userId}, #{title}, 'ACTIVE', #{now}, #{now})
            """)
    int insertConversation(@Param("id") String id, @Param("tenantId") String tenantId,
                           @Param("workspaceId") String workspaceId, @Param("userId") String userId,
                           @Param("title") String title, @Param("now") Instant now);

    @Update("UPDATE conversation SET updated_at = #{now} WHERE id = #{id}")
    int touchConversation(@Param("id") String id, @Param("now") Instant now);

    @Insert("""
            INSERT INTO chat_message (id, tenant_id, conversation_id, request_id, role, content,
                                      status, created_at)
            VALUES (#{id}, #{tenantId}, #{conversationId}, #{requestId}, #{role}, #{content},
                    #{status}, #{now})
            """)
    int insertMessage(@Param("id") String id, @Param("tenantId") String tenantId,
                      @Param("conversationId") String conversationId, @Param("requestId") String requestId,
                      @Param("role") String role, @Param("content") String content,
                      @Param("status") String status, @Param("now") Instant now);

    @Select("""
            SELECT id, tenant_id, workspace_id, user_id, title, status, created_at, updated_at
            FROM conversation
            WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND status <> 'DELETED'
            ORDER BY updated_at DESC LIMIT #{limit}
            """)
    List<ConversationRow> listConversations(@Param("tenantId") String tenantId,
                                            @Param("userId") String userId, @Param("limit") int limit);

    @Select("""
            SELECT id, tenant_id, workspace_id, user_id, title, status, created_at, updated_at
            FROM conversation WHERE id = #{id}
            """)
    ConversationRow findConversation(@Param("id") String id);

    @Select("""
            SELECT id, tenant_id, conversation_id, request_id, role, content, status, created_at
            FROM chat_message WHERE conversation_id = #{conversationId}
            ORDER BY created_at ASC, id ASC LIMIT #{limit}
            """)
    List<MessageRow> listMessages(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Select("""
            SELECT id, tenant_id, conversation_id, request_id, role, content, status, created_at
            FROM chat_message WHERE id = #{id}
            """)
    MessageRow findMessage(@Param("id") String id);

    @Insert("""
            INSERT INTO chat_feedback (id, tenant_id, message_id, actor_id, rating, reason_code, comment,
                                       created_at)
            VALUES (#{id}, #{tenantId}, #{messageId}, #{actorId}, #{rating}, #{reasonCode}, #{comment},
                    #{now})
            ON DUPLICATE KEY UPDATE rating = VALUES(rating), reason_code = VALUES(reason_code),
                                    comment = VALUES(comment), created_at = VALUES(created_at)
            """)
    int upsertFeedback(@Param("id") String id, @Param("tenantId") String tenantId,
                       @Param("messageId") String messageId, @Param("actorId") String actorId,
                       @Param("rating") String rating, @Param("reasonCode") String reasonCode,
                       @Param("comment") String comment, @Param("now") Instant now);

    @Insert("""
            INSERT INTO chat_request (id, message_id, tenant_id, workspace_id, actor_id, kb_id,
                                      conversation_id, acl_version, idempotency_key, status, question,
                                      trace_id, created_at)
            VALUES (#{id}, #{messageId}, #{tenantId}, #{workspaceId}, #{actorId}, #{kbId},
                    #{conversationId}, #{aclVersion}, #{idempotencyKey}, 'RUNNING', #{question},
                    #{traceId}, #{now})
            """)
    int insertRequest(@Param("id") String id, @Param("messageId") String messageId,
                      @Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId,
                      @Param("actorId") String actorId, @Param("kbId") String kbId,
                      @Param("conversationId") String conversationId, @Param("aclVersion") long aclVersion,
                      @Param("idempotencyKey") String idempotencyKey, @Param("question") String question,
                      @Param("traceId") String traceId, @Param("now") Instant now);

    @Update("""
            UPDATE chat_request SET status = #{status}, refusal_reason = #{refusalReason},
                   answer = #{answer}, engine_model = #{engineModel}, model_digest = #{modelDigest},
                   prompt_version = #{promptVersion}, retrieval_config_hash = #{retrievalConfigHash},
                   prompt_chunks = #{promptChunks}, engine_candidates = #{engineCandidates},
                   dropped_by_acl = #{droppedByAcl}, dropped_by_clearance = #{droppedByClearance},
                   dropped_by_stale = #{droppedByStale},
                   dropped_by_unsupported_citation = #{droppedByUnsupportedCitation},
                   authorization_ms = #{authorizationMs}, retrieval_ms = #{retrievalMs},
                   first_token_ms = #{firstTokenMs}, total_ms = #{totalMs},
                   prompt_tokens = #{promptTokens}, completion_tokens = #{completionTokens},
                   error_code = #{errorCode}, completed_at = #{now}
            WHERE id = #{id}
            """)
    int completeRequest(@Param("id") String id, @Param("status") String status,
                        @Param("refusalReason") String refusalReason, @Param("answer") String answer,
                        @Param("engineModel") String engineModel, @Param("modelDigest") String modelDigest,
                        @Param("promptVersion") String promptVersion,
                        @Param("retrievalConfigHash") String retrievalConfigHash,
                        @Param("promptChunks") int promptChunks, @Param("engineCandidates") int engineCandidates,
                        @Param("droppedByAcl") int droppedByAcl, @Param("droppedByClearance") int droppedByClearance,
                        @Param("droppedByStale") int droppedByStale,
                        @Param("droppedByUnsupportedCitation") int droppedByUnsupportedCitation,
                        @Param("authorizationMs") long authorizationMs,
                        @Param("retrievalMs") long retrievalMs, @Param("firstTokenMs") Long firstTokenMs,
                        @Param("totalMs") long totalMs, @Param("promptTokens") Integer promptTokens,
                        @Param("completionTokens") Integer completionTokens, @Param("errorCode") String errorCode,
                        @Param("now") Instant now);

    @Update("""
            UPDATE chat_request SET status = 'CANCELLED', cancelled_at = #{now}, completed_at = #{now}
            WHERE id = #{id} AND status = 'RUNNING'
            """)
    int cancelRequest(@Param("id") String id, @Param("now") Instant now);

    @Select("""
            SELECT id, message_id, tenant_id, workspace_id, actor_id, kb_id, conversation_id,
                   acl_version, idempotency_key, status, refusal_reason, question, answer,
                   engine_model, prompt_chunks, engine_candidates, dropped_by_acl,
                   dropped_by_clearance, dropped_by_stale, dropped_by_unsupported_citation,
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
            INSERT INTO citation (id, message_id, citation_no, tenant_id, request_id, document_id,
                                  document_version, kb_id, engine_doc_id, engine_chunk_id, title,
                                  page_no, section, char_start, char_end, snippet, snippet_digest,
                                  content_digest, source_locator, score, acl_version, acl_verified,
                                  created_at)
            VALUES (#{id}, #{messageId}, #{citationNo}, #{tenantId}, #{requestId}, #{documentId},
                    #{documentVersion}, #{kbId}, #{engineDocId}, #{engineChunkId}, #{title},
                    #{pageNo}, #{section}, #{charStart}, #{charEnd}, #{snippet}, #{snippetDigest},
                    #{contentDigest}, #{sourceLocator}, #{score}, #{aclVersion}, 1, #{now})
            """)
    int insertCitation(@Param("id") String id, @Param("messageId") String messageId,
                       @Param("citationNo") int citationNo, @Param("tenantId") String tenantId,
                       @Param("requestId") String requestId, @Param("documentId") String documentId,
                       @Param("documentVersion") int documentVersion, @Param("kbId") String kbId,
                       @Param("engineDocId") String engineDocId, @Param("engineChunkId") String engineChunkId,
                       @Param("title") String title, @Param("pageNo") Integer pageNo,
                       @Param("section") String section, @Param("charStart") Integer charStart,
                       @Param("charEnd") Integer charEnd, @Param("snippet") String snippet,
                       @Param("snippetDigest") String snippetDigest, @Param("contentDigest") String contentDigest,
                       @Param("sourceLocator") String sourceLocator, @Param("score") double score,
                       @Param("aclVersion") long aclVersion, @Param("now") Instant now);

    @Select("""
            SELECT id, message_id, citation_no, tenant_id, request_id, document_id, document_version,
                   kb_id, engine_doc_id, engine_chunk_id, title, page_no, section, char_start,
                   char_end, snippet, snippet_digest, content_digest, source_locator, score,
                   acl_version, acl_verified, created_at
            FROM citation WHERE id = #{id}
            """)
    CitationRow findCitation(@Param("id") String id);

    @Select("""
            SELECT id, message_id, citation_no, tenant_id, request_id, document_id, document_version,
                   kb_id, engine_doc_id, engine_chunk_id, title, page_no, section, char_start,
                   char_end, snippet, snippet_digest, content_digest, source_locator, score,
                   acl_version, acl_verified, created_at
            FROM citation WHERE request_id = #{requestId} ORDER BY citation_no ASC
            """)
    List<CitationRow> listByRequest(@Param("requestId") String requestId);

    @Select("SELECT COUNT(*) FROM citation")
    long countCitations();
}
