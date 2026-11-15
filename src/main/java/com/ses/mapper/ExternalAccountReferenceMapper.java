package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.entity.ExternalAccountReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExternalAccountReferenceMapper extends BaseMapper<ExternalAccountReference> {

    @Select("SELECT * FROM t_external_account_reference WHERE idempotency_key = #{idempotencyKey} "
            + "AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} AND deleted_flag = 0")
    ExternalAccountReference selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey,
                                                     @Param("tenantId") String tenantId,
                                                     @Param("legalEntityId") Long legalEntityId);

    @Select("SELECT * FROM t_external_account_reference WHERE id = #{id} "
            + "AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} "
            + "AND deleted_flag = 0")
    ExternalAccountReference selectByIdAndScope(@Param("id") Long id,
                                                 @Param("tenantId") String tenantId,
                                                 @Param("legalEntityId") Long legalEntityId);

    /** provider/schedulerが行自身の法人ownershipを信頼して束縛するための取得。 */
    @Select("SELECT * FROM t_external_account_reference WHERE id = #{id} "
            + "AND tenant_id = #{tenantId} AND legal_entity_id IS NOT NULL AND deleted_flag = 0")
    ExternalAccountReference selectOwnedByIdForTenant(@Param("id") Long id,
                                                       @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_external_account_reference WHERE id = #{id} "
            + "AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    ExternalAccountReference selectByIdForUpdate(@Param("id") Long id,
                                                  @Param("tenantId") String tenantId,
                                                  @Param("legalEntityId") Long legalEntityId);

    @Select("SELECT * FROM t_external_account_reference " +
            "WHERE tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} " +
            "  AND assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND status != 'REVOKED' AND deleted_flag = 0")
    List<ExternalAccountReference> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                          @Param("assigneeId") Long assigneeId,
                                                          @Param("tenantId") String tenantId,
                                                          @Param("legalEntityId") Long legalEntityId);

    /** 退社対象は操作者の法人で絞らず、tenant内の全法人を列挙する。 */
    @Select("SELECT * FROM t_external_account_reference WHERE tenant_id = #{tenantId} "
            + "AND assignee_type = 'ENGINEER' AND assignee_id = #{engineerId} "
            + "AND status <> 'REVOKED' AND deleted_flag = 0 ORDER BY id")
    List<ExternalAccountReference> selectUnrevokedForEngineer(@Param("tenantId") String tenantId,
                                                              @Param("engineerId") Long engineerId);

    @Select("SELECT * FROM t_external_account_reference WHERE tenant_id = #{tenantId} "
            + "AND legal_entity_id IS NOT NULL AND deleted_flag = 0 AND status <> 'REVOKED' "
            + "AND idempotency_key IS NOT NULL AND external_sync_status IN "
            + "('REVOKE_REQUESTED','REVOKE_SENDING','REVOKE_SEND_FAILED') "
            + "AND (next_retry_at IS NULL OR next_retry_at <= #{now}) ORDER BY id LIMIT 100")
    List<ExternalAccountReference> selectOutboxForTenant(@Param("tenantId") String tenantId,
                                                        @Param("now") LocalDateTime now);

    @Select("SELECT * FROM t_external_account_reference WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND legal_entity_id IS NOT NULL AND deleted_flag = 0 AND status <> 'REVOKED' "
            + "AND idempotency_key IS NOT NULL AND external_sync_status IN "
            + "('REVOKE_REQUESTED','REVOKE_SENDING','REVOKE_SEND_FAILED') "
            + "AND (next_retry_at IS NULL OR next_retry_at <= #{now})")
    ExternalAccountReference selectOutboxByIdForTenant(@Param("id") Long id,
                                                       @Param("tenantId") String tenantId,
                                                       @Param("now") LocalDateTime now);

    @Update("UPDATE t_external_account_reference SET idempotency_key = #{newKey}, "
            + "status = 'PENDING_CONFIRMATION', external_sync_status = 'REVOKE_REQUESTED', "
            + "revoke_requested_at = #{now}, revoke_requested_by = #{actorId}, retry_count = 0, "
            + "next_retry_at = #{now}, last_error_message = NULL, sync_error_message = NULL, version = version + 1 "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} "
            + "AND version = #{version} AND deleted_flag = 0 AND status <> 'REVOKED' "
            + "AND revoke_confirmed_at IS NULL AND "
            + "((#{oldKey} IS NULL AND idempotency_key IS NULL) OR "
            + "(idempotency_key = #{oldKey} AND status = 'UNKNOWN'))")
    int enqueueRevoke(@Param("id") Long id, @Param("tenantId") String tenantId,
                       @Param("legalEntityId") Long legalEntityId, @Param("version") Integer version,
                       @Param("oldKey") String oldKey, @Param("newKey") String newKey,
                       @Param("actorId") Long actorId, @Param("now") LocalDateTime now);

    /** key・version・送信状態が全て現在値と一致する場合だけ遷移する。 */
    @Update("UPDATE t_external_account_reference SET status = #{status}, external_sync_status = #{toState}, "
            + "retry_count = #{retries}, next_retry_at = #{nextAt}, last_error_message = #{error}, "
            + "version = version + 1 WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND legal_entity_id = #{legalEntityId} AND deleted_flag = 0 AND revoke_confirmed_at IS NULL "
            + "AND status <> 'REVOKED' AND version = #{version} AND idempotency_key = #{key} "
            + "AND external_sync_status = #{fromState}")
    int transitionOutbox(@Param("id") Long id, @Param("tenantId") String tenantId,
                         @Param("legalEntityId") Long legalEntityId, @Param("version") Integer version,
                         @Param("key") String key, @Param("fromState") String fromState,
                         @Param("toState") String toState, @Param("status") String status,
                         @Param("retries") int retries, @Param("nextAt") LocalDateTime nextAt,
                         @Param("error") String error);

    @Select("SELECT * FROM t_external_account_reference "
            + "WHERE tenant_id = #{tenantId} AND legal_entity_id IS NOT NULL "
            + "AND status IN ('PENDING_CONFIRMATION', 'SUSPENDED', 'UNKNOWN') "
            + "AND (external_sync_status IS NULL OR external_sync_status NOT IN ('REVOKE_REQUESTED','REVOKE_SENDING','REVOKE_SEND_FAILED')) "
            + "AND (next_retry_at IS NULL OR next_retry_at <= #{now}) "
            + "AND deleted_flag = 0 ORDER BY id")
    List<ExternalAccountReference> selectPendingForTenant(@Param("tenantId") String tenantId,
                                                           @Param("now") LocalDateTime now);

    @Update("UPDATE t_external_account_reference SET status = 'REVOKED', revoke_confirmed_at = #{confirmedAt}, " +
            "revoke_confirmed_by = #{confirmedBy}, actor_type = #{actorType}, confirmation_source = #{confirmationSource}, " +
            "revoke_confirmed_source = #{confirmationSource}, version = version + 1 " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} " +
            "AND status != 'REVOKED' AND version = #{expectedVersion} AND deleted_flag = 0")
    int confirmRevokeWithCas(@Param("id") Long id,
                             @Param("confirmedAt") LocalDateTime confirmedAt,
                             @Param("confirmedBy") Long confirmedBy,
                             @Param("actorType") String actorType,
                             @Param("confirmationSource") String confirmationSource,
                             @Param("tenantId") String tenantId,
                             @Param("legalEntityId") Long legalEntityId,
                             @Param("expectedVersion") Integer expectedVersion);

    /** 旧呼出し形。受け付ける値は旧MANUAL/SYSTEMまたは新enum値だけに限定する。 */
    default int confirmRevokeWithCas(Long id, LocalDateTime confirmedAt, Long confirmedBy,
                                     String source, String tenantId, Long legalEntityId,
                                     Integer expectedVersion) {
        ConfirmationSource confirmationSource = parseSource(source);
        ActorType actorType = switch (confirmationSource) {
            case MANUAL_API -> ActorType.HUMAN;
            case SCHEDULER_POLL -> ActorType.SYSTEM;
            case PROVIDER_SYNC, PROVIDER_CALLBACK -> ActorType.PROVIDER;
            case LEGACY_UNRESOLVED -> ActorType.LEGACY_UNRESOLVED;
        };
        return confirmRevokeWithCas(id, confirmedAt, confirmedBy, actorType.name(),
                confirmationSource.name(), tenantId, legalEntityId, expectedVersion);
    }

    default int confirmRevokeWithCas(Long id, LocalDateTime confirmedAt, Long confirmedBy,
                                     String tenantId, Long legalEntityId, Integer expectedVersion) {
        return confirmRevokeWithCas(id, confirmedAt, confirmedBy,
                confirmedBy != null ? ActorType.HUMAN.name() : ActorType.SYSTEM.name(),
                confirmedBy != null ? ConfirmationSource.MANUAL_API.name() : ConfirmationSource.SCHEDULER_POLL.name(),
                tenantId, legalEntityId, expectedVersion);
    }

    private static ConfirmationSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("confirmation source is required");
        }
        String normalized = source.trim().toUpperCase();
        if ("MANUAL".equals(normalized)) normalized = ConfirmationSource.MANUAL_API.name();
        if ("SYSTEM".equals(normalized)) normalized = ConfirmationSource.SCHEDULER_POLL.name();
        return ConfirmationSource.valueOf(normalized);
    }

    /**
     * 失効要求の一番最初のclaimだけを成功させる。既存keyの再送はこの更新に入らず、
     * 呼出側がproviderへの再送を行わないことで冪等性を保つ。
     */
    @Update("UPDATE t_external_account_reference SET idempotency_key = #{idempotencyKey}, " +
            "status = 'PENDING_CONFIRMATION', revoke_requested_at = #{requestedAt}, " +
            "revoke_requested_by = #{requestedBy}, " +
            "retry_count = 0, next_retry_at = #{requestedAt}, external_sync_status = 'SYNC_PENDING', " +
            "last_error_message = NULL, sync_error_message = NULL, version = version + 1, " +
            "updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} " +
            "AND deleted_flag = 0 AND revoke_confirmed_at IS NULL " +
            "AND idempotency_key IS NULL " +
            "AND status <> 'REVOKED'")
    int claimRevokeRequest(@Param("id") Long id,
                           @Param("idempotencyKey") String idempotencyKey,
                           @Param("requestedAt") LocalDateTime requestedAt,
                           @Param("requestedBy") Long requestedBy,
                           @Param("tenantId") String tenantId,
                           @Param("legalEntityId") Long legalEntityId);

    /** 同一pending行を複数poll workerが同時にproviderへ問い合わせないよう、短いleaseをCAS取得する。 */
    @Update("UPDATE t_external_account_reference SET next_retry_at = #{leaseUntil}, " +
            "version = version + 1, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} " +
            "AND deleted_flag = 0 " +
            "AND version = #{expectedVersion} " +
            "AND status IN ('PENDING_CONFIRMATION', 'SUSPENDED', 'UNKNOWN') " +
            "AND (external_sync_status IS NULL OR external_sync_status NOT IN ('REVOKE_REQUESTED','REVOKE_SENDING','REVOKE_SEND_FAILED')) " +
            "AND (next_retry_at IS NULL OR next_retry_at <= #{now})")
    int claimRevokePoll(@Param("id") Long id,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("now") LocalDateTime now,
                        @Param("leaseUntil") LocalDateTime leaseUntil,
                        @Param("tenantId") String tenantId,
                        @Param("legalEntityId") Long legalEntityId);

    /** poll workerが取得したclaim versionを保持している場合だけ、結果とbackoffを保存する。 */
    @Update("UPDATE t_external_account_reference SET status = #{status}, " +
            "retry_count = #{retryCount}, next_retry_at = #{nextRetryAt}, " +
            "external_sync_status = #{externalSyncStatus}, last_error_message = #{lastErrorMessage}, " +
            "version = version + 1, updated_at = CURRENT_TIMESTAMP " +
            "WHERE id = #{id} AND tenant_id = #{tenantId} AND legal_entity_id = #{legalEntityId} " +
            "AND deleted_flag = 0 " +
            "AND version = #{claimVersion} AND revoke_confirmed_at IS NULL " +
            "AND status IN ('PENDING_CONFIRMATION', 'SUSPENDED', 'UNKNOWN') " +
            "AND (external_sync_status IS NULL OR external_sync_status NOT IN ('REVOKE_REQUESTED','REVOKE_SENDING','REVOKE_SEND_FAILED'))")
    int completeRevokePollWithCas(@Param("id") Long id,
                                  @Param("status") String status,
                                  @Param("retryCount") Integer retryCount,
                                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                                  @Param("externalSyncStatus") String externalSyncStatus,
                                  @Param("lastErrorMessage") String lastErrorMessage,
                                  @Param("tenantId") String tenantId,
                                  @Param("legalEntityId") Long legalEntityId,
                                  @Param("claimVersion") Integer claimVersion);
}
