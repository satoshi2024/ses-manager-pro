package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 既存rule: 請負で精算時間幅が設定されている（挙動維持・golden fixture対象）。
 * 有効化config keyは既存の "compliance.rule.actual-mismatch.enabled" を維持する。
 */
@Component
public class SettlementMismatchRule extends AbstractComplianceRule {

    public SettlementMismatchRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return "SETTLEMENT_MISMATCH";
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return "請負".equals(contract.getContractType());
    }

    @Override
    protected String enabledKey() {
        return "compliance.rule.actual-mismatch.enabled";
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        if (contract.getSettlementHoursMin() != null || contract.getSettlementHoursMax() != null) {
            return List.of(finding(context, code(), contract.getId(), "settlement-mismatch", null));
        }
        return List.of();
    }
}
