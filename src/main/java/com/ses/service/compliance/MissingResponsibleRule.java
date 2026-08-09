package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * F2 rule: 派遣契約の責任者（指揮命令者・派遣先責任者・派遣元責任者）欠落を検出する。
 * 未設定は派遣ではfinding（準委任の指揮命令者は適用外ルールへ委ねる）。
 */
@Component
public class MissingResponsibleRule extends AbstractComplianceRule {

    public static final String CODE_COMMAND_PERSON = "MISSING_COMMAND_PERSON";
    public static final String CODE_CLIENT_RESPONSIBLE = "MISSING_CLIENT_RESPONSIBLE";
    public static final String CODE_DISPATCH_RESPONSIBLE = "MISSING_DISPATCH_RESPONSIBLE";

    public MissingResponsibleRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return CODE_COMMAND_PERSON;
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
        if (profile.getCommandPersonContactId() == null
                && !StringUtils.hasText(profile.getCommandPersonName())) {
            findings.add(finding(context, CODE_COMMAND_PERSON, contract.getId(), "command-person", null));
        }
        if (profile.getClientResponsibleContactId() == null
                && !StringUtils.hasText(profile.getClientResponsibleName())) {
            findings.add(finding(context, CODE_CLIENT_RESPONSIBLE, contract.getId(), "client-responsible", null));
        }
        if (profile.getDispatchResponsibleUserId() == null
                && !StringUtils.hasText(profile.getDispatchResponsibleName())) {
            findings.add(finding(context, CODE_DISPATCH_RESPONSIBLE, contract.getId(), "dispatch-responsible", null));
        }
        return findings;
    }
}
