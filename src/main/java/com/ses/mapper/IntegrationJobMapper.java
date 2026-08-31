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
     * version もインクリメントして二重実行防止 (platform-invariants §3.2, design §6.3)。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RUNNING', attempt_count = attempt_count + 1, version = version + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND version = #{version} AND status IN ('PENDING', 'RETRYABLE') " +
            "  AND (next_retry_at IS NULL OR next_retry_at <= #{now}) " +
            "  AND deleted_flag = 0")
    int claimJobCas(@Param("id") Long id,
                    @Param("version") int version,
                    @Param("now") LocalDateTime now);

    /**
     * バージョン CAS で RUNNING → SUCCEEDED へ遷移する。
     * 戻り値 1=成功、0=CAS 競合または既に終端状態。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'SUCCEEDED', external_id = #{externalId}, provider_request_id = #{providerRequestId}, " +
            "    sent_at = #{sentAt}, provider_operation_id = #{providerOperationId}, error_code = NULL, error_category = NULL, error_message_safe = NULL, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status = 'RUNNING' AND deleted_flag = 0")
    int transitionToSucceeded(@Param("id") Long id,
                              @Param("version") int version,
                              @Param("externalId") String externalId,
                              @Param("providerRequestId") String providerRequestId,
                              @Param("providerOperationId") String providerOperationId,
                              @Param("sentAt") LocalDateTime sentAt);

    /** プロバイダ応答をローカル状態更新より先に記録し、照合可能にする。 */
    @Update("UPDATE t_integration_job SET external_id = #{externalId}, provider_request_id = #{providerRequestId}, " +
            "provider_operation_id = #{providerOperationId}, updated_at = NOW() WHERE id = #{id} AND deleted_flag = 0")
    int updateProviderMetadata(@Param("id") Long id,
                               @Param("externalId") String externalId,
                               @Param("providerRequestId") String providerRequestId,
                               @Param("providerOperationId") String providerOperationId);

    /**
     * バージョン CAS で RUNNING → RETRYABLE ステータスと次回試行時刻を設定する。
     * 戻り値 1=成功、0=CAS 競合。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RETRYABLE', error_code = #{errorCode}, error_category = #{errorCategory}, error_message_safe = #{safeMessage}, " +
            "    next_retry_at = #{nextRetryAt}, version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status = 'RUNNING' AND deleted_flag = 0")
    int updateStatusToRetryable(@Param("id") Long id,
                                @Param("version") int version,
                                @Param("errorCode") String errorCode,
                                @Param("errorCategory") String errorCategory,
                                @Param("safeMessage") String safeMessage,
                                @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /**
     * バージョン CAS で FAILED へ遷移する (fromStatus 指定)。
     * 戻り値 1=成功、0=CAS 競合。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'FAILED', error_code = #{errorCode}, error_category = #{errorCategory}, error_message_safe = #{safeMessage}, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status = #{fromStatus} AND deleted_flag = 0")
    int transitionToFailed(@Param("id") Long id,
                           @Param("version") int version,
                           @Param("fromStatus") String fromStatus,
                           @Param("errorCode") String errorCode,
                           @Param("errorCategory") String errorCategory,
                           @Param("safeMessage") String safeMessage);

    /**
     * PENDING, RETRYABLE, または RUNNING から CANCELLED へのバージョン CAS 遷移。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'CANCELLED', error_code = 'USER_CANCELLED', error_category = 'BUSINESS', error_message_safe = #{reason}, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status IN ('PENDING', 'RETRYABLE', 'RUNNING') AND deleted_flag = 0")
    int transitionToCancelled(@Param("id") Long id,
                              @Param("version") int version,
                              @Param("reason") String reason);

    /**
     * RETRYABLE または FAILED から PENDING への手動リセット (バージョン CAS)。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'PENDING', next_retry_at = NULL, error_code = NULL, error_category = NULL, error_message_safe = NULL, " +
            "    version = version + 1, updated_at = NOW() " +
            "WHERE id = #{id} AND version = #{version} AND status IN ('RETRYABLE', 'FAILED') AND deleted_flag = 0")
    int transitionToManualRetry(@Param("id") Long id,
                                @Param("version") int version);

    /**
     * RUNNING 状態でリース期限を超過した stale job を RETRYABLE へ一括復旧する。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RETRYABLE', next_retry_at = NOW(), version = version + 1, updated_at = NOW() " +
            "WHERE status = 'RUNNING' AND updated_at < #{staleThreshold} AND deleted_flag = 0")
    int recoverStaleRunning(@Param("staleThreshold") LocalDateTime staleThreshold);
}
