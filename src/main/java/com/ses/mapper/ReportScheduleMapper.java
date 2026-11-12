package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 管理レポートschedule mapper。due claim/completionはtoken付きCASで行う。 */
@Mapper
public interface ReportScheduleMapper extends BaseMapper<ReportSchedule> {

    @Select("SELECT * FROM m_report_schedule WHERE tenant_id = #{tenantId} "
            + "AND enabled = 1 AND deleted_flag = 0 "
            + "AND ((retry_scheduled_at IS NOT NULL AND retry_scheduled_at <= #{now}) "
            + "OR (retry_scheduled_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= #{now} "
            + "     AND processing_claimed_at IS NULL AND processing_claim_token IS NULL) "
            + "OR (processing_claimed_at IS NOT NULL AND processing_claimed_at < #{staleBefore})) "
            + "ORDER BY COALESCE(retry_scheduled_at, processing_logical_run_at, next_run_at), id LIMIT #{limit}")
    List<ReportSchedule> selectDue(@Param("tenantId") String tenantId,
                                   @Param("now") LocalDateTime now,
                                   @Param("staleBefore") LocalDateTime staleBefore,
                                   @Param("limit") int limit);

    @Update("UPDATE m_report_schedule SET processing_logical_run_at = #{logicalRunAt}, "
            + "processing_claimed_at = #{claimedAt}, processing_claim_token = #{claimToken}, "
            + "last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND enabled = 1 AND deleted_flag = 0 "
            + "AND ((#{expectedNextRunAt} IS NULL AND next_run_at IS NULL) OR next_run_at = #{expectedNextRunAt}) "
            + "AND ((#{expectedRetryAt} IS NULL AND retry_scheduled_at IS NULL) OR retry_scheduled_at = #{expectedRetryAt}) "
            + "AND ((#{expectedClaimedAt} IS NULL AND processing_claimed_at IS NULL) OR processing_claimed_at = #{expectedClaimedAt}) "
            + "AND ((#{expectedLogicalRunAt} IS NULL AND processing_logical_run_at IS NULL) OR processing_logical_run_at = #{expectedLogicalRunAt}) "
            + "AND ((#{expectedClaimToken} IS NULL AND processing_claim_token IS NULL) OR processing_claim_token = #{expectedClaimToken}) "
            + "AND ((#{expectedLastRunAt} IS NULL AND last_run_at IS NULL) OR last_run_at = #{expectedLastRunAt}) "
            + "AND failure_count = #{expectedFailureCount} "
            + "AND ((processing_claimed_at IS NULL AND processing_claim_token IS NULL "
            + "      AND retry_scheduled_at IS NOT NULL AND retry_scheduled_at <= #{claimedAt}) "
            + "  OR (processing_claimed_at IS NULL AND processing_claim_token IS NULL "
            + "      AND retry_scheduled_at IS NULL AND next_run_at IS NOT NULL AND next_run_at <= #{claimedAt}) "
            + "  OR (processing_claimed_at IS NOT NULL AND processing_claimed_at < #{staleBefore}))")
    int claimDue(@Param("tenantId") String tenantId, @Param("id") Long id,
                 @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                 @Param("expectedRetryAt") LocalDateTime expectedRetryAt,
                 @Param("expectedClaimedAt") LocalDateTime expectedClaimedAt,
                 @Param("expectedLogicalRunAt") LocalDateTime expectedLogicalRunAt,
                 @Param("expectedClaimToken") String expectedClaimToken,
                 @Param("expectedLastRunAt") LocalDateTime expectedLastRunAt,
                 @Param("expectedFailureCount") Integer expectedFailureCount,
                 @Param("logicalRunAt") LocalDateTime logicalRunAt,
                 @Param("claimToken") String claimToken,
                 @Param("claimedAt") LocalDateTime claimedAt,
                 @Param("staleBefore") LocalDateTime staleBefore);

    @Update("UPDATE m_report_schedule SET next_run_at = #{nextRunAt}, processing_claimed_at = NULL, "
            + "processing_claim_token = NULL, "
            + "processing_logical_run_at = NULL, retry_scheduled_at = NULL, failure_count = 0, "
            + "last_error_code = NULL, last_error_message = NULL, last_run_at = #{logicalRunAt}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} AND id = #{id} "
            + "AND processing_claim_token = #{claimToken} AND processing_logical_run_at = #{logicalRunAt} "
            + "AND processing_claimed_at = #{claimedAt}")
    int markSuccess(@Param("tenantId") String tenantId, @Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("nextRunAt") LocalDateTime nextRunAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt);

    @Update("UPDATE m_report_schedule SET failure_count = COALESCE(failure_count, 0) + 1, "
            + "retry_scheduled_at = #{retryAt}, processing_claimed_at = NULL, processing_claim_token = NULL, "
            + "last_error_code = #{errorCode}, last_error_message = #{errorMessage}, "
            + "last_run_at = #{logicalRunAt}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND processing_claim_token = #{claimToken} "
            + "AND processing_logical_run_at = #{logicalRunAt} AND processing_claimed_at = #{claimedAt}")
    int markFailure(@Param("tenantId") String tenantId, @Param("id") Long id,
                    @Param("claimToken") String claimToken,
                    @Param("claimedAt") LocalDateTime claimedAt,
                    @Param("retryAt") LocalDateTime retryAt,
                    @Param("logicalRunAt") LocalDateTime logicalRunAt,
                    @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage);

    @Update("UPDATE m_report_schedule SET enabled = #{enabled}, updated_by = #{updatedBy}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} AND id = #{id}")
    int updateEnabled(@Param("tenantId") String tenantId, @Param("id") Long id,
                      @Param("enabled") int enabled, @Param("updatedBy") Long updatedBy);
}
