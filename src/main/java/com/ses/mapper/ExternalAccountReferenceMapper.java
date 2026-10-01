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

    @Select("SELECT * FROM t_external_account_reference "
            + "WHERE tenant_id = #{tenantId} AND legal_entity_id IS NOT NULL "
            + "AND status IN ('PENDING_CONFIRMATION', 'SUSPENDED', 'UNKNOWN') "
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
            "AND status IN ('PENDING_CONFIRMATION', 'SUSPENDED', 'UNKNOWN')")
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
