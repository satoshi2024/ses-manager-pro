package com.ses.dto.contract;

import com.ses.dto.compliance.ComplianceFinding;
import lombok.Data;

import java.util.List;

/** 契約登録/更新APIのレスポンスデータ（粗利逆転・労務コンプライアンスリスクの警告を含む。ブロックはしない）。 */
@Data
public class ContractSaveResultDto {
    private Long id;
    /** 保存後の契約行version。次回更新で同じ版を送れるよう画面へ返す。 */
    private Integer version;
    private boolean negativeProfit;
    private List<ComplianceFinding> complianceFindings;
}
