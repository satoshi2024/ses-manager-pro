package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.BpAvailabilityIngestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 要員空き状況メール取込マッパー
 */
@Mapper
public interface BpAvailabilityIngestionMapper extends BaseMapper<BpAvailabilityIngestion> {

    @Select("""
        <script>
        SELECT b.*
          FROM t_bp_availability_ingestion b
          LEFT JOIN sys_user u ON u.id = b.created_by AND u.deleted_flag = 0
         WHERE b.deleted_flag = 0
           AND b.created_by IS NOT NULL
           AND u.tenant_id = #{tenantId}
           <if test="status != null and status != ''">AND b.status = #{status}</if>
         ORDER BY b.created_at DESC, b.id DESC
        </script>
        """)
    Page<BpAvailabilityIngestion> selectPageForTenant(Page<BpAvailabilityIngestion> page,
                                                       @Param("tenantId") String tenantId,
                                                       @Param("status") String status);

    @Select("""
        SELECT b.*
          FROM t_bp_availability_ingestion b
          INNER JOIN sys_user u ON u.id = b.created_by AND u.deleted_flag = 0
         WHERE b.id = #{id} AND b.deleted_flag = 0 AND u.tenant_id = #{tenantId}
        """)
    BpAvailabilityIngestion selectByIdForTenant(@Param("id") Long id,
                                                @Param("tenantId") String tenantId);

    /** 解析workerの状態遷移も、作成者ユーザーのtenant ownershipを条件にする。 */
    @Update("UPDATE t_bp_availability_ingestion b SET status = #{toStatus} "
            + "WHERE b.id = #{id} AND b.status = #{fromStatus} AND b.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int updateStatusForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus);

    /** 解析結果保存をtenant付きでCAS更新する。 */
    @Update("UPDATE t_bp_availability_ingestion b SET status = #{status}, extracted_text = #{extractedText}, "
            + "parsed_json = #{parsedJson}, ai_provider = #{aiProvider}, ai_model = #{aiModel} "
            + "WHERE b.id = #{id} AND b.status = '抽出中' AND b.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int updateParsedForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("status") String status, @Param("extractedText") String extractedText,
                              @Param("parsedJson") String parsedJson, @Param("aiProvider") String aiProvider,
                              @Param("aiModel") String aiModel);

    @Update("UPDATE t_bp_availability_ingestion b SET parsed_json = #{parsedJson}, review_note = #{reviewNote} "
            + "WHERE b.id = #{id} AND b.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int updateReviewForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("parsedJson") String parsedJson, @Param("reviewNote") String reviewNote);

    @Update("UPDATE t_bp_availability_ingestion b SET status = '確定済', converted_availability_id = #{availabilityId}, "
            + "review_note = #{reviewNote} WHERE b.id = #{id} AND b.status = '要確認' "
            + "AND b.converted_availability_id IS NULL AND b.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int confirmForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                         @Param("availabilityId") Long availabilityId, @Param("reviewNote") String reviewNote);

    @Update("UPDATE t_bp_availability_ingestion b SET status = '却下', error_message = #{reason} "
            + "WHERE b.id = #{id} AND b.deleted_flag = 0 AND b.status IN ('取込待ち','抽出中','要確認','失敗') "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int rejectForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                        @Param("reason") String reason);

    @Update("UPDATE t_bp_availability_ingestion b SET status = '失敗', error_message = #{message} "
            + "WHERE b.id = #{id} AND b.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = b.created_by "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0)")
    int updateFailedForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("message") String message);

    /**
     * 孤児ファイル清理用：却下以外の論理削除されていないジョブの stored_file_name を取得する。
     */
    @Select("SELECT stored_file_name FROM t_bp_availability_ingestion " +
            "WHERE deleted_flag = 0 AND status != '\u5374\u4e0b' AND stored_file_name IS NOT NULL")
    List<String> selectAllStoredFileNames();
}
