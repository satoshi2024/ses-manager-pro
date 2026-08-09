package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.List;

/** 既存rule: 準委任/請負で顧客が直接指揮命令する（挙動維持・golden fixture対象）。 */
@Component
public class DirectCommandRule extends AbstractComplianceRule {

    private static final List<String> DIRECT_COMMAND_CONTRACT_TYPES = List.of("準委任", "請負");

    public DirectCommandRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return "DIRECT_COMMAND";
    }

    @Override
    public boolean appliesTo(Contract contract) {
        String contractType = contract.getContractType();
        return contractType != null && DIRECT_COMMAND_CONTRACT_TYPES.contains(contractType);
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        if (Boolean.TRUE.equals(contract.getDirectCommandFlag())) {
            return List.of(finding(context, code(), contract.getId(), "direct-command",
                    null, contract.getContractType()));
        }
        return List.of();
    }
}
