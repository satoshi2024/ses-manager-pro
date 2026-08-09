package com.ses.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

/**
 * 文書HashアトミックClaimマッパー。
 */
@Mapper
public interface DocumentHashClaimMapper {

    @Insert("""
        INSERT INTO t_document_hash_claim
            (tenant_id, document_type, sha256, document_id, created_at)
        VALUES
            (#{tenantId}, #{documentType}, #{sha256}, #{documentId}, CURRENT_TIMESTAMP)
        """)
    int insertClaim(@Param("tenantId") String tenantId,
                    @Param("documentType") String documentType,
                    @Param("sha256") String sha256,
                    @Param("documentId") Long documentId);

    @Select("""
        SELECT COUNT(*)
        FROM t_document_hash_claim
        WHERE tenant_id = #{tenantId}
          AND document_type = #{documentType}
          AND sha256 = #{sha256}
        """)
    long countClaim(@Param("tenantId") String tenantId,
                   @Param("documentType") String documentType,
                   @Param("sha256") String sha256);
}
