package com.ses.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 生成前に評価したrecipient scopeの結果。拒否理由は安全な分類コードだけを返す。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportRecipientPreview {
    private Long recipientUserId;
    private String recipientRole;
    private String scopeDecision;
    private String reasonCode;
    private String recipientScopeHash;
}
