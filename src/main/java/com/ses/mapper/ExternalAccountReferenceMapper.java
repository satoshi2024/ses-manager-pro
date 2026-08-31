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
            + "AND deleted_flag = 0")
    ExternalAccountReference selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    @Select("SELECT * FROM t_external_account_reference WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    ExternalAccountReference selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_external_account_reference " +
            "WHERE assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND status != 'REVOKED' AND deleted_flag = 0")
    List<ExternalAccountReference> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                          @Param("assigneeId") Long assigneeId);

    @Update("UPDATE t_external_account_reference SET status = 'REVOKED', revoke_confirmed_at = #{confirmedAt}, " +
            "revoke_confirmed_by = #{confirmedBy}, actor_type = #{actorType}, confirmation_source = #{confirmationSource}, " +
            "revoke_confirmed_source = #{confirmationSource}, version = version + 1 " +
            "WHERE id = #{id} AND status != 'REVOKED' AND version = #{expectedVersion} AND deleted_flag = 0")
    int confirmRevokeWithCas(@Param("id") Long id,
                             @Param("confirmedAt") LocalDateTime confirmedAt,
                             @Param("confirmedBy") Long confirmedBy,
                             @Param("actorType") String actorType,
                             @Param("confirmationSource") String confirmationSource,
                             @Param("expectedVersion") Integer expectedVersion);

    /** 旧呼出し形。受け付ける値は旧MANUAL/SYSTEMまたは新enum値だけに限定する。 */
    default int confirmRevokeWithCas(Long id,
                                     LocalDateTime confirmedAt,
                                     Long confirmedBy,
                                     String source,
                                     Integer expectedVersion) {
        ConfirmationSource confirmationSource = parseSource(source);
        ActorType actorType = switch (confirmationSource) {
            case MANUAL_API -> ActorType.HUMAN;
            case SCHEDULER_POLL -> ActorType.SYSTEM;
            case PROVIDER_SYNC, PROVIDER_CALLBACK -> ActorType.PROVIDER;
            case LEGACY_UNRESOLVED -> ActorType.LEGACY_UNRESOLVED;
        };
        return confirmRevokeWithCas(id, confirmedAt, confirmedBy, actorType.name(),
                confirmationSource.name(), expectedVersion);
    }

    default int confirmRevokeWithCas(Long id,
                                     LocalDateTime confirmedAt,
                                     Long confirmedBy,
                                     Integer expectedVersion) {
        return confirmRevokeWithCas(id, confirmedAt, confirmedBy,
                confirmedBy != null ? ActorType.HUMAN.name() : ActorType.SYSTEM.name(),
                confirmedBy != null ? ConfirmationSource.MANUAL_API.name() : ConfirmationSource.SCHEDULER_POLL.name(),
                expectedVersion);
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
            "WHERE id = #{id} AND deleted_flag = 0 AND revoke_confirmed_at IS NULL " +
            "AND idempotency_key IS NULL " +
            "AND status <> 'REVOKED'")
    int claimRevokeRequest(@Param("id") Long id,
                           @Param("idempotencyKey") String idempotencyKey,
                           @Param("requestedAt") LocalDateTime requestedAt,
                           @Param("requestedBy") Long requestedBy);

    default int claimRevokeRequest(Long id, String idempotencyKey, LocalDateTime requestedAt) {
        return claimRevokeRequest(id, idempotencyKey, requestedAt, null);
    }
}
