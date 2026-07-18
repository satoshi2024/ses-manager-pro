package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 日次勤怠。物理行（月次合計の生成元。合計は t_work_record.actual_hours へ集約）。
 */
@Data
@TableName("t_work_record_daily")
public class WorkRecordDaily {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long workRecordId;
    private LocalDate workDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer breakMinutes;
    /** 稼働時間(自動計算値を保存)。 */
    private BigDecimal workedHours;
    private String remarks;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
