package com.ses.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 将来稼働率・Bench予測レスポンスDTO（FR-07）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationForecastDto {

    /** 月次予測一覧 */
    private List<MonthlyForecastDto> monthlyForecasts;

    /** ロールオフ予定要員一覧 */
    private List<RolloffEngineerDto> rolloffEngineers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyForecastDto {
        /** 月表示名（例: "2026/08" または "8月"） */
        private String month;
        /** 年月文字列（例: "2026-08"） */
        private String yearMonth;
        /** 稼働見込み数 */
        private int workingCount;
        /** Bench見込み数 */
        private int benchCount;
        /** 総要員数 */
        private int totalCount;
        /** 稼働率見込み（%） */
        private double utilizationRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RolloffEngineerDto {
        /** 要員ID */
        private Long engineerId;
        /** 要員名 */
        private String engineerName;
        /** イニシャル表記 */
        private String initialName;
        /** 契約ID */
        private Long contractId;
        /** 契約番号 */
        private String contractNo;
        /** 案件ID */
        private Long projectId;
        /** 案件名 */
        private String projectName;
        /** 顧客ID */
        private Long customerId;
        /** 顧客名 */
        private String customerName;
        /** 担当営業ID */
        private Long salesUserId;
        /** 担当営業名 */
        private String salesUserName;
        /** 契約終了日 (yyyy/MM/dd) */
        private String endDate;
        /** 対象月 (yyyy-MM) */
        private String targetMonth;
        /** 自動更新 (1:する, 0:しない) */
        private Integer autoRenew;
        /** 更新判断 ('CONTINUE'/'END'/null) */
        private String renewalDecision;
    }
}
