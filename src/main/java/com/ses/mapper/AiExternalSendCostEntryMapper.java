package com.ses.mapper;

import com.ses.entity.AiExternalSendCostEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * NF08 永続費用ledgerの送信ごとの予約entry。annotation SQLだけを持ち、汎用CRUD（BaseMapper）を継承しない。
 * <p>reservation_id（BINARY(16)）は小文字36文字表記のclaim IDを {@code UUID_TO_BIN(?, 0)} で保存し、
 * {@code BIN_TO_UUID(?, 0)} で同じ表記へ戻す（計画 D-2）。</p>
 */
@Mapper
public interface AiExternalSendCostEntryMapper {

    /** claim transaction内（DAY・MONTHのlockの後）でRESERVEDのentryを1件作る。 */
    @Insert("INSERT INTO t_ai_external_send_cost_entry (reservation_id, tenant_id, run_id, trace_id, cost_scope, "
            + "day_key, month_key, decision_id, approval_digest, reserved_yen, entry_state, status_version, created_at) "
            + "VALUES (UUID_TO_BIN(#{e.reservationId}, 0), #{e.tenantId}, #{e.runId}, #{e.traceId}, #{e.costScope}, "
            + "#{e.dayKey}, #{e.monthKey}, #{e.decisionId}, #{e.approvalDigest}, #{e.reservedYen}, 'RESERVED', 0, "
            + "#{e.createdAt})")
    int insertReserved(@Param("e") AiExternalSendCostEntry entry);

    @Select("SELECT id, BIN_TO_UUID(reservation_id, 0) AS reservation_id, tenant_id, run_id, trace_id, cost_scope, "
            + "day_key, month_key, decision_id, approval_digest, reserved_yen, settled_yen, entry_state, "
            + "status_version, created_at, closed_at FROM t_ai_external_send_cost_entry "
            + "WHERE reservation_id = UUID_TO_BIN(#{reservationId}, 0)")
    AiExternalSendCostEntry selectByReservationId(@Param("reservationId") String reservationId);
}
