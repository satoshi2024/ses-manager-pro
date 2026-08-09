package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 月次就業状況・タイムシート（LEDGER_WORK_HISTORY）。
 * 締め時点snapshotの反復行。雇用勤怠とは分離した客先工数を保持する。
 * 訂正・取消はevent_type=CORRECTED/CANCELLEDの新行INSERTで行い、旧行は不変。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ledger_work_snapshot")
public class LedgerWorkSnapshot extends BaseEntity {

    private String tenantId;
    private Long contractId;
    private Long workerId;
    private String eventId;
    private String eventType;
    private String supersedesEventId;
    private String correctionReason;
    private Long actorUserId;
    private LocalDateTime occurredAt;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private LocalDate workMonth;
    private Integer workDays;
    private Integer workHours;
    private Integer overtimeHours;
    private Integer absenceDays;
    private BigDecimal grossAmount;
    private LocalDateTime closedAt;

    @Version
    private Integer version;
}
