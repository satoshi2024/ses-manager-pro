package com.ses.dto.compliance;

import lombok.Data;

import java.util.List;

/**
 * 現在リスク該当している契約1件分（管理者向けリスク一覧画面用）。
 */
@Data
public class ContractComplianceDto {
    private Long contractId;
    private String contractNo;
    private String engineerName;
    private String projectName;
    private String contractType;
    private List<ComplianceFinding> findings;
}
