package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** 既存rule: 派遣でBP階層が2以上（二重派遣兆候）（挙動維持・golden fixture対象）。 */
@Component
public class DoubleDispatchRule extends AbstractComplianceRule {

    public DoubleDispatchRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return "DOUBLE_DISPATCH";
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return "派遣".equals(contract.getContractType());
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        if (context.maxLayer() > 1) {
            return List.of(finding(context, code(), contract.getId(), "double-dispatch",
                    null, context.maxLayer()));
        }
        return List.of();
    }
}
