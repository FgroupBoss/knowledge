package com.acme.kb.p1.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DocumentAclMapper {

    record Row(String id, String tenantId, String documentId, String subjectType, String subjectId,
                String grantType) {
    }

    @Select("""
            SELECT id, tenant_id, document_id, subject_type, subject_id, grant_type
            FROM document_acl WHERE document_id = #{documentId} AND subject_type = 'USER' AND subject_id = #{userId}
            """)
    List<Row> listForDocumentAndUser(@Param("documentId") String documentId, @Param("userId") String userId);

    @Select("SELECT COUNT(*) FROM document_acl WHERE document_id = #{documentId}")
    int countForDocument(@Param("documentId") String documentId);

    @Select("""
            SELECT grant_type FROM document_acl
            WHERE document_id = #{documentId} AND subject_type = 'USER' AND subject_id = #{userId}
              AND grant_type IN ('READ','WRITE')
            """)
    List<String> grantsOf(@Param("documentId") String documentId, @Param("userId") String userId);
}
