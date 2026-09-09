package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.ResumeIngestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** スキルシート取込マッパー。全ての業務更新はtenant/id/status/version CASを通す。 */
@Mapper
public interface ResumeIngestionMapper extends BaseMapper<ResumeIngestion> {

    @Select("""
        <script>
        SELECT r.* FROM t_resume_ingestion r
         WHERE r.deleted_flag = 0 AND r.tenant_id = #{tenantId}
           <if test="status != null and status != ''">AND r.status = #{status}</if>
         ORDER BY r.created_at DESC, r.id DESC
        </script>
        """)
    Page<ResumeIngestion> selectPageForTenant(Page<ResumeIngestion> page,
                                               @Param("tenantId") String tenantId,
                                               @Param("status") String status);

    @Select("SELECT r.* FROM t_resume_ingestion r WHERE r.id = #{id} "
            + "AND r.tenant_id = #{tenantId} AND r.deleted_flag = 0")
    ResumeIngestion selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_resume_ingestion WHERE stored_file_name = #{storedName} "
            + "AND tenant_id = #{tenantId} AND deleted_flag = 0 LIMIT 1")
    ResumeIngestion selectByStoredFileNameForTenant(@Param("tenantId") String tenantId,
                                                    @Param("storedName") String storedName);

    /** 他tenantの原本名を認可根拠にしないための存在検知。返却値は認可母集団に使わない。 */
    @Select("SELECT COUNT(*) FROM t_resume_ingestion WHERE stored_file_name = #{storedName} "
            + "AND deleted_flag = 0")
    int countByStoredFileName(@Param("storedName") String storedName);

    @Update("UPDATE t_resume_ingestion SET status = #{toStatus}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = #{fromStatus} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int casStatusForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                           @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
                           @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET status = #{status}, extracted_text = #{extractedText}, "
            + "parsed_json = #{parsedJson}, ai_provider = #{aiProvider}, ai_model = #{aiModel}, "
            + "version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '抽出中' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int saveParsedForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                            @Param("status") String status, @Param("extractedText") String extractedText,
                            @Param("parsedJson") String parsedJson, @Param("aiProvider") String aiProvider,
                            @Param("aiModel") String aiModel, @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET parsed_json = #{parsedJson}, review_note = #{reviewNote}, "
            + "version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '要確認' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int saveReviewForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                            @Param("parsedJson") String parsedJson, @Param("reviewNote") String reviewNote,
                            @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET status = '確定済', converted_engineer_id = #{engineerId}, "
            + "review_note = #{reviewNote}, version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND status = '要確認' AND converted_engineer_id IS NULL AND version = #{expectedVersion} "
            + "AND deleted_flag = 0")
    int confirmForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                         @Param("engineerId") Long engineerId, @Param("reviewNote") String reviewNote,
                         @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET status = '却下', error_message = #{reason}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status IN ('取込待ち','抽出中','要確認','失敗') "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int rejectForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                        @Param("reason") String reason, @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET status = '失敗', error_message = #{message}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '抽出中' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int updateFailedForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("message") String message, @Param("expectedVersion") Integer expectedVersion);

    @Select("SELECT stored_file_name FROM t_resume_ingestion WHERE tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 AND status != '却下' AND stored_file_name IS NOT NULL")
    List<String> selectAllStoredFileNamesForTenant(@Param("tenantId") String tenantId);

    @Select("SELECT r.* FROM t_resume_ingestion r WHERE r.tenant_id = #{tenantId} "
            + "AND r.deleted_flag = 0 AND r.status IN ('確定済','却下') "
            + "AND r.updated_at < #{cutoff} AND r.stored_file_name IS NOT NULL "
            + "ORDER BY r.updated_at, r.id")
    List<ResumeIngestion> selectExpiredOriginalsForTenant(@Param("tenantId") String tenantId,
                                                          @Param("cutoff") LocalDateTime cutoff);

    /** 原本・抽出結果を消去し、監査用ジョブ行と状態履歴は残す。versionで再実行を冪等化する。 */
    @Update("UPDATE t_resume_ingestion SET original_file_name = NULL, stored_file_name = NULL, "
            + "file_ext = NULL, extracted_text = NULL, parsed_json = NULL, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = #{status} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int purgeOriginalForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                               @Param("status") String status, @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_resume_ingestion SET deleted_flag = 1, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '取込待ち' "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int deleteNewJobForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                              @Param("expectedVersion") Integer expectedVersion);
}
