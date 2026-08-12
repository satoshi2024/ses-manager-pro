package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import com.ses.entity.DocumentDelivery;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * F2 rule: 派遣契約の法定帳票（就業条件明示書・派遣先通知書）の交付記録がないことを検出する。
 * document_typeの正式enumはB1で確定するため、本ruleは安定constantでfingerprintを分ける。
 * confirmed_at IS NULLは「受領未確認」（未交付ではない）ため、交付記録の有無だけを判定する。
 */
@Component
public class MissingDocumentDeliveryRule extends AbstractComplianceRule {

    public static final String CODE = "MISSING_DOCUMENT_DELIVERY";

    /** B1で正式enum化するまで本ruleが使用するdocument_type定数。 */
    public static final String DOC_TYPE_EMPLOYMENT_CONDITIONS = "EMPLOYMENT_CONDITIONS_STATEMENT";
    public static final String DOC_TYPE_DISPATCH_NOTICE = "DISPATCH_NOTICE";

    public MissingDocumentDeliveryRule(SystemConfigService systemConfigService, MessageSource messageSource) {
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
        // 交付記録の有無だけで判定するためprofileに依存しない（profile未作成でも検出する）。
        List<ComplianceFinding> findings = new ArrayList<>();
        for (String documentType : List.of(DOC_TYPE_EMPLOYMENT_CONDITIONS, DOC_TYPE_DISPATCH_NOTICE)) {
            boolean delivered = context.deliveries().stream()
                    .anyMatch(d -> documentType.equals(d.getDocumentType())
                            && "DELIVERED".equals(d.getDeliveryStatus()));
            if (!delivered) {
                String label = DOC_TYPE_EMPLOYMENT_CONDITIONS.equals(documentType)
                        ? "就業条件明示書" : "派遣先通知書";
                findings.add(finding(context, CODE, contract.getId(), "DOC:" + documentType, null, label));
            }
        }
        return findings;
    }
}
