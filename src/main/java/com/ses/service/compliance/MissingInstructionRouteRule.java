package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * F2 rule: 準委任/請負の作業指示経路が未設定/空であることを検出する（R3.2）。
 * 指示経路・勤怠承認者・直接指示記録の不足は偽装請負リスクの警告対象。
 */
@Component
public class MissingInstructionRouteRule extends AbstractComplianceRule {

    public static final String CODE = "MISSING_INSTRUCTION_ROUTE";

    public MissingInstructionRouteRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean appliesTo(Contract contract) {
        String contractType = contract.getContractType();
        return "準委任".equals(contractType) || "請負".equals(contractType);
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        ContractComplianceProfile profile = context.profile();
        // profile未作成は「全field未入力」として検出する（design §5.1: 未入力＝MISSING_* finding対象）。
        if (profile == null || !StringUtils.hasText(profile.getInstructionRoute())) {
            return List.of(finding(context, CODE, contract.getId(), "instruction-route", null));
        }
        return List.of();
    }
}
