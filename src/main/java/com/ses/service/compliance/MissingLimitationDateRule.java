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
 * F2 rule: 2種の抵触日が未算定（NULL）を検出する（design §5.1）。
 * NULLは「抵触日なし＝安全」ではなく未算定であり、算定chainから導出できる抵触日をdueDateとして添付する。
 * chain算定は LimitationDateCalculator（design §5.2）が行う。
 */
@Component
public class MissingLimitationDateRule extends AbstractComplianceRule {

    public static final String CODE_WORKPLACE = "MISSING_WORKPLACE_LIMITATION_DATE";
    public static final String CODE_ORGANIZATION = "MISSING_ORGANIZATION_LIMITATION_DATE";

    private final LimitationDateCalculator limitationDateCalculator;

    public MissingLimitationDateRule(SystemConfigService systemConfigService, MessageSource messageSource,
                                     LimitationDateCalculator limitationDateCalculator) {
        super(systemConfigService, messageSource);
        this.limitationDateCalculator = limitationDateCalculator;
    }

    @Override
    public String code() {
        return CODE_WORKPLACE;
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return "派遣".equals(contract.getContractType());
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        ContractComplianceProfile profile = context.profile();
        Long workplaceId = profile != null ? profile.getWorkplaceId() : null;
        LimitationDateCalculator.LimitationDates computed = limitationDateCalculator.compute(
                contract.getStartDate(), workplaceId, context.organizationUnit(),
                context.contractChain());
        List<ComplianceFinding> findings = new ArrayList<>();
        // profile未作成は「全field未入力」として検出する（design §5.1: 未入力＝MISSING_* finding対象）。
        if (profile == null || profile.getWorkplaceLimitationDate() == null) {
            String fingerprint = "workplace:" + (workplaceId != null
                    ? workplaceId : contract.getCustomerId());
            findings.add(finding(context, CODE_WORKPLACE, contract.getId(), fingerprint,
                    computed != null ? computed.workplaceDate() : null));
        }
        if (profile == null || profile.getOrganizationLimitationDate() == null) {
            String fingerprint = "org:" + (context.organizationUnit() != null
                    ? context.organizationUnit() : "unknown");
            findings.add(finding(context, CODE_ORGANIZATION, contract.getId(), fingerprint,
                    computed != null ? computed.organizationDate() : null));
        }
        return findings;
    }
}
