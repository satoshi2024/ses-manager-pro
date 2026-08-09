package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ContractComplianceDto;
import com.ses.dto.compliance.RuleRunResultDto;
import com.ses.service.compliance.ComplianceRuleEngine;
import com.ses.service.compliance.LaborComplianceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * コンプライアンス・リスク一覧API（既存 / FR-10系。管理者/マネージャーのみ許可）。
 * t_role_menu の compliance メニュー（menu permission filter）にのみ許可される。
 * F2: POST /rules/run は全ruleを実行して t_compliance_finding へupsertする（read-only＋upsert）。
 */
@RestController
@RequestMapping("/api/compliance")
@RequiredArgsConstructor
public class ComplianceApiController {

    private final LaborComplianceService laborComplianceService;
    private final ComplianceRuleEngine complianceRuleEngine;

    @GetMapping("/findings")
    public ApiResult<List<ContractComplianceDto>> findings() {
        return ApiResult.success(laborComplianceService.findCurrentRisks());
    }

    @PostMapping("/rules/run")
    public ApiResult<RuleRunResultDto> runRules() {
        ComplianceRuleEngine.RunResult result = complianceRuleEngine.runActiveContracts();
        return ApiResult.success(new RuleRunResultDto(
                result.contractsEvaluated(), result.opened(), result.resolved(), result.kept()));
    }
}
