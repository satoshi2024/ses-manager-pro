package com.ses.dto.compliance;

import lombok.Data;

/**
 * 法定帳票の生成リクエスト（T064 B1）。
 */
@Data
public class ComplianceDocumentGenerateRequest {

    /** EMPLOYMENT_CONDITIONS_STATEMENT / DISPATCH_NOTICE / DISPATCH_LEDGER / INDIVIDUAL_CONTRACT */
    private String documentType;

    /** EMAIL / PORTAL / PAPER / OTHER */
    private String deliveryMethod;

    /** 受領者（顧客担当者）。省略可。 */
    private Long recipientContactId;
}
