package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.ProjectIngestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 案件メール取込ジョブMapper
 */
@Mapper
public interface ProjectIngestionMapper extends BaseMapper<ProjectIngestion> {

    @Select("""
            <script>
            SELECT p.* FROM t_project_ingestion p
            WHERE p.tenant_id = #{tenantId}
              AND p.deleted_flag = 0
            <if test="status != null and status != ''">
              AND p.status = #{status}
            </if>
            ORDER BY p.created_at DESC, p.id DESC
            </script>
            """)
    Page<ProjectIngestion> selectPageForTenant(Page<ProjectIngestion> page,
                                               @Param("tenantId") String tenantId,
                                               @Param("status") String status);

    @Select("SELECT * FROM t_project_ingestion WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0")
    ProjectIngestion selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_project_ingestion WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    ProjectIngestion selectByIdForUpdateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT stored_file_name FROM t_project_ingestion WHERE tenant_id = #{tenantId} "
            + "AND source_type = 'EML' AND stored_file_name IS NOT NULL AND deleted_flag = 0")
    List<String> selectAllStoredFileNamesForTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_project_ingestion WHERE stored_file_name = #{storedFileName} "
            + "AND tenant_id = #{tenantId} AND deleted_flag = 0 LIMIT 1")
    ProjectIngestion selectByStoredFileNameForTenant(@Param("tenantId") String tenantId,
                                                    @Param("storedFileName") String storedFileName);

    @Select("SELECT COUNT(*) FROM t_project_ingestion WHERE stored_file_name = #{storedFileName} "
            + "AND deleted_flag = 0")
    int countByStoredFileName(@Param("storedFileName") String storedFileName);

    @Update("UPDATE t_project_ingestion SET status = #{toStatus}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = #{fromStatus} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int casStatusForTenant(@Param("id") Long id,
                           @Param("tenantId") String tenantId,
                           @Param("fromStatus") String fromStatus,
                           @Param("toStatus") String toStatus,
                           @Param("expectedVersion") Integer expectedVersion);

    @Update("""
            <script>
            UPDATE t_project_ingestion
            SET status = '要確認', raw_text = #{rawText}, parsed_json = #{parsedJson},
                ai_provider = #{aiProvider}, ai_model = #{aiModel}, version = version + 1
            WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '抽出中'
              AND version = #{expectedVersion} AND deleted_flag = 0
            </script>
            """)
    int saveParsedForTenant(@Param("id") Long id,
                            @Param("tenantId") String tenantId,
                            @Param("rawText") String rawText,
                            @Param("parsedJson") String parsedJson,
                            @Param("aiProvider") String aiProvider,
                            @Param("aiModel") String aiModel,
                            @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_project_ingestion SET parsed_json = #{parsedJson}, review_note = #{reviewNote}, "
            + "version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND status = '要確認' AND version = #{expectedVersion} AND deleted_flag = 0")
    int saveReviewForTenant(@Param("id") Long id,
                            @Param("tenantId") String tenantId,
                            @Param("parsedJson") String parsedJson,
                            @Param("reviewNote") String reviewNote,
                            @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_project_ingestion SET status = '確定済', converted_project_id = #{projectId}, "
            + "review_note = #{reviewNote}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND status = '要確認' "
            + "AND converted_project_id IS NULL AND version = #{expectedVersion} AND deleted_flag = 0")
    int confirmForTenant(@Param("id") Long id,
                         @Param("tenantId") String tenantId,
                         @Param("projectId") Long projectId,
                         @Param("reviewNote") String reviewNote,
                         @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_project_ingestion SET status = '却下', error_message = #{reason}, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND status IN ('取込待ち', '抽出中', '要確認', '失敗') "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int rejectForTenant(@Param("id") Long id,
                        @Param("tenantId") String tenantId,
                        @Param("reason") String reason,
                        @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_project_ingestion SET status = '失敗', error_message = #{message}, "
            + "version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND status = '抽出中' AND version = #{expectedVersion} AND deleted_flag = 0")
    int updateFailedForTenant(@Param("id") Long id,
                              @Param("tenantId") String tenantId,
                              @Param("message") String message,
                              @Param("expectedVersion") Integer expectedVersion);
}
