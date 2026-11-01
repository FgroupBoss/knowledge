package com.acme.kb.p0.repo;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * 参考知识引擎（stub provider）自己的存储层。业务代码只能经 KnowledgeEnginePort 访问，
 * 不得从业务服务直接查询这些表——这里刻意不返回 document/object_key 等业务列。
 */
@Mapper
public interface EngineStoreMapper {

    record ChunkRow(String id, String engineChunkId, String engineDocId, String engineKbId,
                    String tenantId, String workspaceId, String kbId, String documentId,
                    int documentVer, String classification, int chunkNo, Integer pageNo,
                    String section, Integer charStart, Integer charEnd, String content,
                    String contentDigest, byte[] embedding, String embeddingModel, int tokenCount) {
    }

    record DocRow(String engineDocId, String engineKbId, String tenantId, String workspaceId,
                  String kbId, String documentId, int documentVer, String sha256, boolean searchable) {
    }

    @Insert("""
            INSERT INTO engine_kb (engine_kb_id, tenant_id, workspace_id, kb_id, classification,
                                   acl_version, created_at)
            VALUES (#{engineKbId}, #{tenantId}, #{workspaceId}, #{kbId}, #{classification},
                    #{aclVersion}, #{now})
            ON DUPLICATE KEY UPDATE classification = VALUES(classification),
                                      acl_version = VALUES(acl_version)
            """)
    int upsertKb(@Param("engineKbId") String engineKbId, @Param("tenantId") String tenantId,
                 @Param("workspaceId") String workspaceId, @Param("kbId") String kbId,
                 @Param("classification") String classification, @Param("aclVersion") long aclVersion,
                 @Param("now") Instant now);

    @Select("SELECT engine_kb_id FROM engine_kb WHERE kb_id = #{kbId}")
    String findEngineKbId(@Param("kbId") String kbId);

    /** 引擎侧 ACL 快照版本：低于业务库 acl_version 时该 KB 的召回一律视为过期，不得进入 Prompt。 */
    @Select("SELECT acl_version FROM engine_kb WHERE engine_kb_id = #{engineKbId}")
    Long aclVersionOfEngineKb(@Param("engineKbId") String engineKbId);

    /** 只有把新权限真正交给引擎之后才允许抬版；抬版失败保持低版本，召回继续 fail-closed。 */
    @Update("UPDATE engine_kb SET acl_version = #{aclVersion} WHERE engine_kb_id = #{engineKbId}")
    int syncAclVersion(@Param("engineKbId") String engineKbId, @Param("aclVersion") long aclVersion);

    @Insert("""
            INSERT INTO engine_document (engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id,
                                         document_id, document_ver, sha256, searchable, parse_meta, indexed_at)
            VALUES (#{engineDocId}, #{engineKbId}, #{tenantId}, #{workspaceId}, #{kbId},
                    #{documentId}, #{documentVer}, #{sha256}, 1, #{parseMeta}, #{now})
            ON DUPLICATE KEY UPDATE engine_doc_id = VALUES(engine_doc_id),
                                     searchable = 1, indexed_at = VALUES(indexed_at)
            """)
    int upsertDocument(@Param("engineDocId") String engineDocId, @Param("engineKbId") String engineKbId,
                       @Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId,
                       @Param("kbId") String kbId, @Param("documentId") String documentId,
                       @Param("documentVer") int documentVer, @Param("sha256") String sha256,
                       @Param("parseMeta") String parseMeta, @Param("now") Instant now);

    @Select("""
            SELECT engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id, document_id,
                   document_ver, sha256, searchable
            FROM engine_document WHERE document_id = #{documentId} AND document_ver = #{version}
            """)
    DocRow findDocument(@Param("documentId") String documentId, @Param("version") int version);

    @Update("UPDATE engine_document SET searchable = #{searchable} WHERE engine_doc_id = #{engineDocId}")
    int setSearchable(@Param("engineDocId") String engineDocId, @Param("searchable") boolean searchable);

    @Delete("DELETE FROM engine_chunk WHERE engine_doc_id = #{engineDocId}")
    int deleteChunks(@Param("engineDocId") String engineDocId);

    @Delete("DELETE FROM engine_document WHERE engine_doc_id = #{engineDocId}")
    int deleteDocument(@Param("engineDocId") String engineDocId);

    @Insert("""
            INSERT INTO engine_chunk (id, engine_chunk_id, engine_doc_id, engine_kb_id, tenant_id,
                                      workspace_id, kb_id, document_id, document_ver, classification,
                                      chunk_no, page_no, section, char_start, char_end, content,
                                      content_digest, embedding, embedding_model, token_count, created_at)
            VALUES (#{id}, #{engineChunkId}, #{engineDocId}, #{engineKbId}, #{tenantId},
                    #{workspaceId}, #{kbId}, #{documentId}, #{documentVer}, #{classification},
                    #{chunkNo}, #{pageNo}, #{section}, #{charStart}, #{charEnd}, #{content},
                    #{contentDigest}, #{embedding}, #{embeddingModel}, #{tokenCount}, #{now})
            """)
    int insertChunk(@Param("id") String id, @Param("engineChunkId") String engineChunkId,
                    @Param("engineDocId") String engineDocId, @Param("engineKbId") String engineKbId,
                    @Param("tenantId") String tenantId, @Param("workspaceId") String workspaceId,
                    @Param("kbId") String kbId, @Param("documentId") String documentId,
                    @Param("documentVer") int documentVer, @Param("classification") String classification,
                    @Param("chunkNo") int chunkNo, @Param("pageNo") Integer pageNo,
                    @Param("section") String section, @Param("charStart") Integer charStart,
                    @Param("charEnd") Integer charEnd, @Param("content") String content,
                    @Param("contentDigest") String contentDigest, @Param("embedding") byte[] embedding,
                    @Param("embeddingModel") String embeddingModel, @Param("tokenCount") int tokenCount,
                    @Param("now") Instant now);

    @Select("""
            <script>
            SELECT id, engine_chunk_id, engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id,
                   document_id, document_ver, classification, chunk_no, page_no, section,
                   char_start, char_end, content, content_digest, embedding, embedding_model, token_count
            FROM engine_chunk
            WHERE engine_kb_id IN
            <foreach item="k" collection="engineKbIds" open="(" separator="," close=")">#{k}</foreach>
            </script>
            """)
    List<ChunkRow> loadChunksForKbs(@Param("engineKbIds") List<String> engineKbIds);

    /** 召回前过滤：范围（engine_kb_id）与租户在 SQL 层就已限定，可下线文档不参与召回。 */
    @Select("""
            <script>
            SELECT id, engine_chunk_id, engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id,
                   document_id, document_ver, classification, chunk_no, page_no, section,
                   char_start, char_end, content, content_digest, embedding, embedding_model, token_count
            FROM engine_chunk c
            WHERE c.tenant_id = #{tenantId}
              AND c.engine_kb_id IN
            <foreach item="k" collection="engineKbIds" open="(" separator="," close=")">#{k}</foreach>
              AND EXISTS (SELECT 1 FROM engine_document d
                          WHERE d.engine_doc_id = c.engine_doc_id AND d.searchable = 1)
            </script>
            """)
    List<ChunkRow> loadSearchableChunks(@Param("tenantId") String tenantId,
                                        @Param("engineKbIds") List<String> engineKbIds);

    /** 仅用于注入"引擎越界返回"攻击样例（p0.engine.leak-out-of-scope=true）。 */
    @Select("""
            <script>
            SELECT id, engine_chunk_id, engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id,
                   document_id, document_ver, classification, chunk_no, page_no, section,
                   char_start, char_end, content, content_digest, embedding, embedding_model, token_count
            FROM engine_chunk c
            WHERE c.engine_kb_id NOT IN
            <foreach item="k" collection="excludedKbIds" open="(" separator="," close=")">#{k}</foreach>
            ORDER BY c.created_at DESC LIMIT #{limit}
            </script>
            """)
    List<ChunkRow> loadChunksOutsideScope(@Param("excludedKbIds") List<String> excludedKbIds,
                                          @Param("limit") int limit);

    @Select("""
            SELECT id, engine_chunk_id, engine_doc_id, engine_kb_id, tenant_id, workspace_id, kb_id,
                   document_id, document_ver, classification, chunk_no, page_no, section,
                   char_start, char_end, content, content_digest, embedding, embedding_model, token_count
            FROM engine_chunk WHERE engine_chunk_id = #{engineChunkId}
            """)
    ChunkRow findChunk(@Param("engineChunkId") String engineChunkId);

    @Select("SELECT COUNT(*) FROM engine_chunk")
    long countChunks();

    @Select("SELECT COUNT(*) FROM engine_document WHERE searchable = 1")
    long countSearchableDocuments();
}
