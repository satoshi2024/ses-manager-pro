package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文書版Mapper（append-only）。
 */
@Mapper
public interface DocumentVersionMapper extends BaseMapper<DocumentVersion> {

    /**
     * 文書の全版を版番号昇順で取得する。
     */
    @Select("SELECT * FROM t_document_version WHERE document_id = #{documentId} AND deleted_flag = 0 ORDER BY version_no ASC")
    List<DocumentVersion> findByDocumentId(@Param("documentId") Long documentId);

    /**
     * 文書の最新版（最大version_no）を取得する。
     */
    @Select("SELECT * FROM t_document_version WHERE document_id = #{documentId} AND deleted_flag = 0 ORDER BY version_no DESC LIMIT 1")
    DocumentVersion findLatestByDocumentId(@Param("documentId") Long documentId);

    /**
     * 冪等キーで既存版を検索する（tenant_id対応）。
     */
    @Select("""
        SELECT * FROM t_document_version
        WHERE tenant_id = #{tenantId}
          AND source_type = #{sourceType}
          AND business_key = #{businessKey}
          AND version_discriminator = #{versionDiscriminator}
          AND deleted_flag = 0
        LIMIT 1
        """)
    DocumentVersion findByIdempotencyKey(
            @Param("tenantId") String tenantId,
            @Param("sourceType") String sourceType,
            @Param("businessKey") String businessKey,
            @Param("versionDiscriminator") String versionDiscriminator);
}
