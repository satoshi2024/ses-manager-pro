package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.IntegrationJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IntegrationJobMapper extends BaseMapper<IntegrationJob> {

    /**
     * PENDING または RETRYABLE な job を RUNNING に claim する条件付き CAS 更新。
     * next_retry_at が NULL または現在時刻以前のものだけを対象にする。
     * 二重実行防止 (platform-invariants §3.2, design §6.3)。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RUNNING', attempt_count = attempt_count + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND status IN ('PENDING', 'RETRYABLE') " +
            "  AND (next_retry_at IS NULL OR next_retry_at <= #{now}) " +
            "  AND deleted_flag = 0")
    int claimJob(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * バージョン CAS で RUNNING → SUCCEEDED へ遷移する。
     * 戻り値 1=成功、0=CAS 競合または既に終端状態。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'SUCCEEDED', external_id = #{externalId}, provider_request_id = #{providerRequestId}, " +
            "    sent_at = #{sentAt}, error_code = NULL, error_message_safe = NULL, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status = 'RUNNING' AND deleted_flag = 0")
    int transitionToSucceeded(@Param("id") Long id,
                              @Param("version") int version,
                              @Param("externalId") String externalId,
                              @Param("providerRequestId") String providerRequestId,
                              @Param("sentAt") LocalDateTime sentAt);

    /**
     * バージョン CAS でステータスとエラー情報を更新する（FAILED / CANCELLED / RETRYABLE 用）。
     * 戻り値 1=成功、0=CAS 競合。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = #{toStatus}, error_code = #{errorCode}, error_message_safe = #{safeMessage}, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND deleted_flag = 0")
    int updateStatusWithError(@Param("id") Long id,
                              @Param("version") int version,
                              @Param("toStatus") String toStatus,
                              @Param("errorCode") String errorCode,
                              @Param("safeMessage") String safeMessage);

    /**
     * バージョン CAS で RETRYABLE ステータスと次回試行時刻を設定する。
     * 戻り値 1=成功、0=CAS 競合。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RETRYABLE', error_code = #{errorCode}, error_message_safe = #{safeMessage}, " +
            "    next_retry_at = #{nextRetryAt}, version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND deleted_flag = 0")
    int updateStatusToRetryable(@Param("id") Long id,
                                @Param("version") int version,
                                @Param("errorCode") String errorCode,
                                @Param("safeMessage") String safeMessage,
                                @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
