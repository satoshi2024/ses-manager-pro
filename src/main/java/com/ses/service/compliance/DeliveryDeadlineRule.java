package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.DocumentDelivery;
import com.ses.service.SystemConfigService;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * T066 M（外部専門家Review P2-1/P2-2対応）: 法定帳票の交付期限rule。
 *  - DEADLINE_DOCUMENT_DELIVERY: 就業条件明示書は派遣開始日の前日までに労働者へ交付（派遣法34条の2）。
 *  - DEADLINE_DISPATCH_NOTICE: 派遣先通知書は派遣開始後遅滞なく（施行規則20条）。
 *    猶予日数はm_system_config（compliance.delivery.notice-grace-days、既定3日・運用基準）。
 *  - 両ruleとも期限の90日前から未交付の間はfindingを存在させ、dueDateをT065の90/60/30日前
 *    通知基盤へ渡す（P2-C: 期限超過後のみの発火では前倒し通知が成立しないため）。
 *  - 設計意図（P3-R3）: 通知書の義務は派遣開始後に発生するが、findingは期限前からの計画的警告であり、
 *    期限90日前からの発火により段階通知（90/60/30）が順に成立する。発火起点を開始日にすると
 *    due=開始+猶予に対して90/60/30の全段階が同時発火するため、前倒しwindowを維持する。
 * 法的値の最終確認はGATE-T060-EXTERNAL/GATE-T066-FIELD-SEMANTICS（本実装はconfig既定値で駆動し、コードへ直書きしない）。
 */
@Component
public class DeliveryDeadlineRule extends AbstractComplianceRule {

    public static final String CODE_DOCUMENT = "DEADLINE_DOCUMENT_DELIVERY";
    public static final String CODE_NOTICE = "DEADLINE_DISPATCH_NOTICE";

    /** 派遣先通知書の猶予日数（派遣開始日から）。法的値はgate確認待ちのためconfig値。 */
    public static final String CONFIG_NOTICE_GRACE_DAYS = "compliance.delivery.notice-grace-days";

    public DeliveryDeadlineRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return CODE_DOCUMENT;
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return "派遣".equals(contract.getContractType());
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        ContractComplianceProfile profile = context.profile();
        if (profile == null || profile.getDispatchPeriodStart() == null) {
            // 派遣開始日が未設定では期限を算定できない（算定不能は発火しない）
            return List.of();
        }
        LocalDate today = LocalDate.now();
        List<ComplianceFinding> findings = new ArrayList<>();

        // P2-1: 就業条件明示書（労働者本人へ交付義務）は派遣開始日の前日まで
        // 期限の90日前から未交付の間はfindingを存在させる（T065の90/60/30日前通知基盤へdueDateを渡す。
        // 期限超過後も未交付の間は継続発火し、交付記録があればstoreがRESOLVEDへ同期する）。
        boolean statementDelivered = context.deliveries().stream().anyMatch(d ->
                MissingDocumentDeliveryRule.DOC_TYPE_EMPLOYMENT_CONDITIONS.equals(d.getDocumentType())
                        && "DELIVERED".equals(d.getDeliveryStatus()));
        LocalDate statementDue = profile.getDispatchPeriodStart().minusDays(1);
        if (!statementDelivered && !today.isBefore(statementDue.minusDays(90))) {
            findings.add(finding(context, CODE_DOCUMENT, contract.getId(), "DOC:EMPLOYMENT_CONDITIONS_STATEMENT", statementDue));
        }

        // P2-2: 派遣先通知書は派遣開始後遅滞なく（猶予日数はconfig・運用基準）
        boolean noticeDelivered = context.deliveries().stream().anyMatch(d ->
                MissingDocumentDeliveryRule.DOC_TYPE_DISPATCH_NOTICE.equals(d.getDocumentType())
                        && "DELIVERED".equals(d.getDeliveryStatus()));
        int graceDays = configInt(CONFIG_NOTICE_GRACE_DAYS, 3);
        LocalDate noticeDue = profile.getDispatchPeriodStart().plusDays(graceDays);
        if (!noticeDelivered && !today.isBefore(noticeDue.minusDays(90))) {
            findings.add(finding(context, CODE_NOTICE, contract.getId(), "DOC:DISPATCH_NOTICE", noticeDue));
        }
        return findings;
    }
}
