package com.ses.dto.attendance.discrepancy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 客先工数差異（R4.1）のread-only DTO。
 *
 * <p>雇用勤怠合計（分）と契約工数（分）を月次で比較し、閾値超過を理由付きで確認できる。
 * 金額計算・請求ロジックへ一切接続しない（R4.2 / design §5.4）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceDiscrepancyDto {
    private String workMonth;
    private Integer thresholdMinutes;
    private List<Item> items;

    public static AttendanceDiscrepancyDto empty(String workMonth, Integer thresholdMinutes) {
        return AttendanceDiscrepancyDto.builder()
                .workMonth(workMonth)
                .thresholdMinutes(thresholdMinutes)
                .items(new ArrayList<>())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long engineerId;
        private String engineerName;
        private Long legalEntityId;
        private Long organizationId;
        /** 雇用勤怠合計（分） */
        private Integer attendanceMinutes;
        /** 契約工数合計（分。actual_hours×60） */
        private Integer contractMinutes;
        /** 差異（分）＝雇用勤怠 − 契約工数 */
        private Integer diffMinutes;
        /** 閾値超過（|diff| >= threshold） */
        private boolean overThreshold;
        /** 確認済みか */
        private boolean confirmed;
        /** 確認理由 */
        private String reason;
        /** 確認日時 */
        private String confirmedAt;
        /** 確認者 */
        private String confirmedBy;
    }
}
