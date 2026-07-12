package com.ses.dto.analytics;

import lombok.Data;

/**
 * 稼動率推移(月次1ポイント分)DTO
 */
@Data
public class UtilizationPointDto {
    /** 対象年月 (例: "2026-07") */
    private String yearMonth;
    /** 表示用ラベル (例: "7月") */
    private String label;
    /** 稼動要員数(月末時点で稼動中契約を持つ要員数) */
    private int activeCount;
    /** Bench数(在籍要員数 - 稼動要員数) */
    private int benchCount;
    /** 在籍要員数(月末時点で登録済みの要員数) */
    private int totalCount;
    /** 稼動率(%、小数第1位まで、totalCount が 0 の場合は null) */
    private Double utilizationRate;
}
