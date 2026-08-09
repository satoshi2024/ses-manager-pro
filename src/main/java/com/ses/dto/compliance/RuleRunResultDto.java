package com.ses.dto.compliance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** F2 rule実行（POST /api/compliance/rules/run）の集計結果。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuleRunResultDto {
    private int contractsEvaluated;
    private int opened;
    private int resolved;
    private int kept;
}
