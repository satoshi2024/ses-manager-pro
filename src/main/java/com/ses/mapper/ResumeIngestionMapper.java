package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ResumeIngestion;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * スキルシート取込マッパー
 */
@Mapper
public interface ResumeIngestionMapper extends BaseMapper<ResumeIngestion> {

    @Select("""
        <script>
        SELECT r.*
          FROM t_resume_ingestion r
          LEFT JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
          LEFT JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
         WHERE r.deleted_flag = 0
           AND (r.created_by IS NULL OR u.tenant_id = #{tenantId})
           AND (r.converted_engineer_id IS NULL OR e.tenant_id = #{tenantId})
           AND (r.created_by IS NOT NULL OR r.converted_engineer_id IS NOT NULL)
           <if test="status != null and status != ''">AND r.status = #{status}</if>
         ORDER BY r.created_at DESC, r.id DESC
        </script>
        """)
    Page<ResumeIngestion> selectPageForTenant(Page<ResumeIngestion> page,
                                               @Param("tenantId") String tenantId,
                                               @Param("status") String status);

    @Select("""
        SELECT r.*
          FROM t_resume_ingestion r
          LEFT JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
          LEFT JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
         WHERE r.id = #{id} AND r.deleted_flag = 0
           AND (r.created_by IS NULL OR u.tenant_id = #{tenantId})
           AND (r.converted_engineer_id IS NULL OR e.tenant_id = #{tenantId})
           AND (r.created_by IS NOT NULL OR r.converted_engineer_id IS NOT NULL)
        """)
    ResumeIngestion selectByIdForTenant(@Param("id") Long id,
                                        @Param("tenantId") String tenantId);

    /**
     * 孤児ファイル清理用：却下以外の論理削除されていないジョブの stored_file_name を取得する。
     */
    @Select("SELECT stored_file_name FROM t_resume_ingestion " +
            "WHERE deleted_flag = 0 AND status != '\u5374\u4e0b' AND stored_file_name IS NOT NULL")
    List<String> selectAllStoredFileNames();
}
