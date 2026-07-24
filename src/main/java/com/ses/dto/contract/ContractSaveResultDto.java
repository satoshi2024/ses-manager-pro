package com.ses.dto.contract;

import com.ses.dto.compliance.ComplianceFinding;
import lombok.Data;

import java.util.List;

/** 契約登録/更新APIのレスポンスデータ（粗利逆転・労務コンプライアンスリスクの警告を含む。ブロックはしない）。 */
@Data
public class ContractSaveResultDto {
    private Long id;
    private boolean negativeProfit;
    private List<ComplianceFinding> complianceFindings;
}
