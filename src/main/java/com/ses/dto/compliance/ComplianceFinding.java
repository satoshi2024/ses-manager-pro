package com.ses.dto.compliance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * コンプライアンス検査ruleの1件分のfinding（既存 / FR-10系）。
 * ブロックは表示用のみ。追加コードは
 * TIER_EXCEEDED / DIRECT_COMMAND / DOUBLE_DISPATCH / SETTLEMENT_MISMATCH に加え
 * MISSING_* / DEADLINE_* / RISK_* 系（F2）がある。
 * conditionFingerprint / dueDate は永続化（t_compliance_finding upsert）用のrule出力であり、
 * 既存画面は表示しない。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceFinding {
    private String code;
    private String severity;
    private String message;
    private Long contractId;
    private String conditionFingerprint;
    private LocalDate dueDate;

    /** 既存呼び出し（表示用）互換コンストラクタ。 */
    public ComplianceFinding(String code, String severity, String message, Long contractId) {
        this.code = code;
        this.severity = severity;
        this.message = message;
        this.contractId = contractId;
    }
}
