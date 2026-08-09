package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** 既存rule: BP支払階層が上限を超える（挙動維持・golden fixture対象）。 */
@Component
public class TierExceededRule extends AbstractComplianceRule {

    public TierExceededRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return "TIER_EXCEEDED";
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return true;
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        int maxTier = configInt("compliance.max-tier", 3);
        if (context.maxLayer() > maxTier) {
            return List.of(finding(context, code(), contract.getId(), "tier",
                    null, context.maxLayer(), maxTier));
        }
        return List.of();
    }
}
