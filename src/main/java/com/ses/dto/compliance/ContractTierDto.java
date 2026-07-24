package com.ses.dto.compliance;

import lombok.Data;

/** 契約1件分の最大BP階層番号（selectMaxLayerOrderGroupedByContract の集計結果行）。 */
@Data
public class ContractTierDto {
    private Long contractId;
    private Integer maxLayer;
}
