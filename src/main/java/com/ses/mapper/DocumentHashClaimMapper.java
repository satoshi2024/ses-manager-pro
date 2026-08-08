package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentHashClaim;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 文書HashアトミックClaimマッパー。
 */
@Mapper
public interface DocumentHashClaimMapper extends BaseMapper<DocumentHashClaim> {

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
