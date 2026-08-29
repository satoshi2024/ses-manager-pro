package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
            "revoke_confirmed_by = #{confirmedBy}, version = version + 1 " +
            "WHERE id = #{id} AND status != 'REVOKED' AND version = #{expectedVersion} AND deleted_flag = 0")
    int confirmRevokeWithCas(@Param("id") Long id,
                             @Param("confirmedAt") LocalDateTime confirmedAt,
                             @Param("confirmedBy") Long confirmedBy,
                             @Param("expectedVersion") Integer expectedVersion);

    /**
     * 失効要求の一番最初のclaimだけを成功させる。既存keyの再送はこの更新に入らず、
     * 呼出側がproviderへの再送を行わないことで冪等性を保つ。
     */
    @Update("UPDATE t_external_account_reference SET idempotency_key = #{idempotencyKey}, "
            + "status = 'PENDING_CONFIRMATION', revoke_requested_at = #{requestedAt}, "
            + "retry_count = 0, next_retry_at = #{requestedAt}, external_sync_status = 'SYNC_PENDING', "
            + "last_error_message = NULL, sync_error_message = NULL, version = version + 1, "
            + "updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0 AND revoke_confirmed_at IS NULL "
            + "AND (idempotency_key IS NULL OR idempotency_key = #{idempotencyKey}) "
            + "AND status <> 'REVOKED'")
    int claimRevokeRequest(@Param("id") Long id,
                           @Param("idempotencyKey") String idempotencyKey,
                           @Param("requestedAt") LocalDateTime requestedAt);
}
