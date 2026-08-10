package com.ses.dto.attendance.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;

/** 外部providerから取得した雇用勤怠レコード（read-only照合用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalAttendanceRecord {
    /** 外部側の一意ID（冪等キー。t_employee_attendance.source_external_idへ保存） */
    private String sourceExternalId;
    /** 外部側の従業員ID（FreeeEmployeeLinkで要員へ解決する） */
    private String externalEngineerId;
    /** 本システム側の要員ID（解決済みなら設定） */
    private Long engineerId;
    private LocalDate workDate;
    private LocalTime clockIn;
    private LocalTime clockOut;
    private Integer breakMinutes;
    private Integer regularMinutes;
    private Integer overtimeMinutes;
    private Integer holidayMinutes;
    private Integer lateNightMinutes;
    private String workType;
    /** 外部側の更新日時（cursor判定に使う） */
    private String updatedAt;
}
