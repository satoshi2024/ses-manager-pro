package com.ses.dto.compliance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 労務コンプライアンスリスク該当1件（偽装請負・多重派遣リスクチェック / FR-10）。
 * ブロックはせず警告のみ。ルールコードは
 * TIER_EXCEEDED / DIRECT_COMMAND / DOUBLE_DISPATCH / SETTLEMENT_MISMATCH。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceFinding {
    private String code;
    private String severity;
    private String message;
    private Long contractId;
}
