package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 雇用勤怠日次の休憩区間（方式A / R2-P1-02）。
 * 区間は勤務開始を0とする整数分offsetで保存し、跨夜でも日付を曖昧にしない。
 * 論理削除列を持たず、親のt_employee_attendance行と同一トランザクションで
 * 置換・削除する（履歴復元を不要にする物理子行）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_employee_attendance_break")
public class EmployeeAttendanceBreak implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long attendanceId;

    /** 開始offset昇順の区間番号（1始まり）。隣接は許可、重複は拒否。 */
    private Integer sequenceNo;

    private Integer startOffsetMinutes;

    private Integer endOffsetMinutes;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
