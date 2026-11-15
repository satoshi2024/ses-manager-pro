package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文書業務リンクMapper。 */
@Mapper
public interface DocumentLinkMapper extends BaseMapper<DocumentLink> {

    /** 現在スコープ内の資産貸与証跡・紛失インシデント文書IDをSQL母集団として取得する。 */
    @Select("""
            <script>
            SELECT DISTINCT dl.document_id
            FROM t_document_link dl
            JOIN t_document d ON d.id = dl.document_id AND d.deleted_flag = 0
            WHERE dl.deleted_flag = 0
              AND (
                (
                  dl.target_type = 'ASSET_ASSIGNMENT'
                  AND EXISTS (
                    SELECT 1 FROM t_asset_assignment aa
                    WHERE aa.id = dl.target_id AND aa.deleted_flag = 0
                      AND aa.asset_id IN
                      <foreach collection="assetIds" item="assetId" open="(" separator="," close=")">#{assetId}</foreach>
                  )
                )
                OR (
                  dl.target_type = 'ASSET_LOST_INCIDENT'
                  AND EXISTS (
                    SELECT 1 FROM t_asset_lost_incident li
                    WHERE li.id = dl.target_id AND li.deleted_flag = 0
                      AND li.asset_id IN
                      <foreach collection="assetIds" item="assetId" open="(" separator="," close=")">#{assetId}</foreach>
                  )
                )
              )
            </script>
            """)
    List<Long> selectDocumentIdsByAssetIds(@Param("assetIds") List<Long> assetIds);

    /** 要員本人のassignment証跡。返却後もassignment履歴に紐づく本人文書は保持する。 */
    @Select("""
            <script>
            SELECT DISTINCT dl.document_id
            FROM t_document_link dl
            JOIN t_asset_assignment aa ON aa.id = dl.target_id
            JOIN t_document d ON d.id = dl.document_id AND d.deleted_flag = 0
            WHERE dl.deleted_flag = 0 AND aa.deleted_flag = 0
              AND dl.target_type = 'ASSET_ASSIGNMENT'
              AND aa.assignee_type = 'ENGINEER'
              AND aa.assignee_id IN
              <foreach collection="engineerIds" item="engineerId" open="(" separator="," close=")">#{engineerId}</foreach>
            </script>
            """)
    List<Long> selectDocumentIdsByEngineerIds(@Param("engineerIds") List<Long> engineerIds);

    /**
     * 指定した業務エンティティ種別・IDにリンクされた文書IDを全て取得する。
     * 認可母集団の和集合計算に使用する（design §6.2）。
     */
    @Select("SELECT document_id FROM t_document_link WHERE target_type = #{targetType} "
            + "AND target_id = #{targetId} AND deleted_flag = 0")
    List<Long> findDocumentIdsByTarget(@Param("targetType") String targetType,
                                       @Param("targetId") Long targetId);

    @Select("SELECT document_id FROM t_document_link WHERE tenant_id = #{tenantId} AND target_type = #{targetType} "
            + "AND target_id = #{targetId} AND deleted_flag = 0")
    List<Long> findDocumentIdsByTargetForTenant(@Param("tenantId") String tenantId,
                                                @Param("targetType") String targetType,
                                                @Param("targetId") Long targetId);

    /**
     * 文書IDに紐づく全リンクを取得する。
     */
    @Select("SELECT * FROM t_document_link WHERE document_id = #{documentId} AND deleted_flag = 0")
    List<DocumentLink> findByDocumentId(@Param("documentId") Long documentId);

    @Select("SELECT * FROM t_document_link WHERE tenant_id = #{tenantId} "
            + "AND document_id = #{documentId} AND deleted_flag = 0")
    List<DocumentLink> findByDocumentIdForTenant(@Param("tenantId") String tenantId,
                                                 @Param("documentId") Long documentId);

    /**
     * link一意key（document_id, target_type, target_id）の既存行をlocking readで取得する（NF01 F3）。
     *
     * <p>{@code DocumentLinkCommandService} 専用。文書行をX lockした後に呼び、tenantと論理削除の有無を問わず
     * 一意keyの行を返す（一意keyは論理削除済みの行も含むため）。返す列は照合に使う列だけに限る。</p>
     */
    @Select("SELECT id, tenant_id, document_id, target_type, target_id, deleted_flag FROM t_document_link "
            + "WHERE document_id = #{documentId} AND target_type = #{targetType} AND target_id = #{targetId} FOR UPDATE")
    DocumentLink selectByLinkKeyForUpdate(@Param("documentId") long documentId,
                                          @Param("targetType") String targetType,
                                          @Param("targetId") long targetId);
}
