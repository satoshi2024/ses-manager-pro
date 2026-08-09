package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * F2 rule: 派遣契約の社会保険（健康/厚生年金/雇用）の加入状態が未確認（NULL）を検出する。
 * 未確認は「不要」ではない（SRC-Lの提出有無・未加入理由・取得予定日と分離して保持する）。
 */
@Component
public class MissingInsuranceRule extends AbstractComplianceRule {

    public static final String CODE = "MISSING_INSURANCE_CONFIRMATION";

    public MissingInsuranceRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return "派遣".equals(contract.getContractType());
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        ContractComplianceProfile profile = context.profile();
        if (profile == null) {
            return List.of();
        }
        List<ComplianceFinding> findings = new ArrayList<>();
        if (profile.getHealthInsuranceStatus() == null) {
            findings.add(finding(context, CODE, contract.getId(), "HEALTH", null, "健康保険"));
        }
        if (profile.getPensionInsuranceStatus() == null) {
            findings.add(finding(context, CODE, contract.getId(), "PENSION", null, "厚生年金"));
        }
        if (profile.getEmploymentInsuranceStatus() == null) {
            findings.add(finding(context, CODE, contract.getId(), "EMPLOYMENT", null, "雇用保険"));
        }
        return findings;
    }
}
