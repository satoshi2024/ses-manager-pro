package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 管理レポートschedule mapper。due claimはprocessing leaseのCASで行う。 */
@Mapper
public interface ReportScheduleMapper extends BaseMapper<ReportSchedule> {

    @Select("SELECT * FROM m_report_schedule WHERE enabled = 1 AND deleted_flag = 0 "
            + "AND ((retry_scheduled_at IS NOT NULL AND retry_scheduled_at <= #{now}) "
            + "OR (retry_scheduled_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= #{now} "
            + "     AND processing_claimed_at IS NULL) "
            + "OR (processing_claimed_at IS NOT NULL AND processing_claimed_at < #{staleBefore})) "
            + "ORDER BY COALESCE(retry_scheduled_at, processing_logical_run_at, next_run_at), id LIMIT #{limit}")
    List<ReportSchedule> selectDue(@Param("now") LocalDateTime now,
                                   @Param("staleBefore") LocalDateTime staleBefore,
                                   @Param("limit") int limit);

    @Update("UPDATE m_report_schedule SET processing_logical_run_at = #{logicalRunAt}, "
            + "processing_claimed_at = #{runAt}, last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND enabled = 1 AND deleted_flag = 0 "
            + "AND (processing_claimed_at IS NULL OR processing_claimed_at < #{staleBefore}) "
            + "AND ((retry_scheduled_at IS NOT NULL AND retry_scheduled_at <= #{runAt}) "
            + "     OR (retry_scheduled_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= #{runAt} "
            + "         AND next_run_at = #{expectedNextRunAt}))")
    int claimDue(@Param("id") Long id, @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                 @Param("logicalRunAt") LocalDateTime logicalRunAt, @Param("runAt") LocalDateTime runAt,
                 @Param("staleBefore") LocalDateTime staleBefore);

    @Update("UPDATE m_report_schedule SET next_run_at = #{nextRunAt}, processing_claimed_at = NULL, "
            + "processing_logical_run_at = NULL, retry_scheduled_at = NULL, failure_count = 0, "
            + "last_error_code = NULL, last_error_message = NULL, last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND processing_claimed_at IS NOT NULL")
    int markSuccess(@Param("id") Long id, @Param("nextRunAt") LocalDateTime nextRunAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt);

    @Update("UPDATE m_report_schedule SET failure_count = COALESCE(failure_count, 0) + 1, "
            + "retry_scheduled_at = #{retryAt}, processing_claimed_at = NULL, "
            + "last_error_code = #{errorCode}, last_error_message = #{errorMessage}, "
            + "last_run_at = #{logicalRunAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND processing_claimed_at IS NOT NULL")
    int markFailure(@Param("id") Long id, @Param("retryAt") LocalDateTime retryAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt,
                    @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);
}
