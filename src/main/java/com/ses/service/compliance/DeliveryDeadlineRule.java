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
 *  - DEADLINE_DOCUMENT_DELIVERY: 就業条件明示書は派遣開始前に労働者本人へ明示（派遣法第34条。相手=労働者本人、
 *    「あらかじめ」。施行規則上、開始日の前日までに運用）。
 *  - DEADLINE_DISPATCH_NOTICE: 派遣先通知書は派遣するとき派遣先へ通知（派遣法第35条。相手=派遣先。
 *    「遅滞なく」の運用基準として猶予日数をconfig化: compliance.delivery.notice-grace-days、既定3日）。
 *  - 条文引用の注意: 派遣法第34条の2は「労働者派遣に関する料金の額の明示」（派遣元→労働者）であり、
 *    P1-1（個別契約書への派遣料金明示）の根拠条文。就業条件明示は第34条（一次source照合による訂正）。
 *  - 発火:
 *    - 明示書: 期限（=開始前日）の90日前から未交付の間はfindingを存在させ、dueDateをT065の90/60/30日前
 *      通知基盤へ渡す（P2-C: 期限超過後のみの発火では前倒し通知が成立しないため）。
 *    - 通知書: 通知義務は派遣開始後に発生するため（法35条）、**発火起点は派遣開始日**。
 *      dueDate=開始日+猶予日数をT065基盤へ渡し、banded段階（90/60/30日window）で通知する。
 *  - 設計意図（P3-R3）: 明示書は「あらかじめ」義務のため開始前に計画的警告を発火。通知書は開始後義務のため
 *    開始日から発火し、banded stagingにより期限前の誤通知（例: 開始87日前に90日前通知）を発生させない。
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

        // P2-1: 就業条件明示書（労働者本人へ交付義務・派遣法第34条）は派遣開始前日まで
        // 期限の90日前から未交付の間はfindingを存在させる（T065の90/60/30日前通知基盤へdueDateを渡す。
        // 期限超過後も未交付の間は継続発火し、交付記録があればstoreがRESOLVEDへ同期する）。
        boolean statementDelivered = context.deliveries().stream().anyMatch(d ->
                MissingDocumentDeliveryRule.DOC_TYPE_EMPLOYMENT_CONDITIONS.equals(d.getDocumentType())
                        && "DELIVERED".equals(d.getDeliveryStatus()));
        LocalDate statementDue = profile.getDispatchPeriodStart().minusDays(1);
        if (!statementDelivered && !today.isBefore(statementDue.minusDays(90))) {
            findings.add(finding(context, CODE_DOCUMENT, contract.getId(), "DOC:EMPLOYMENT_CONDITIONS_STATEMENT", statementDue));
        }

        // P2-2: 派遣先通知書は派遣するとき派遣先へ通知（派遣法第35条）。義務は開始後に発生するため
        // 発火起点は派遣開始日（P3-R3）。dueDate=開始日+猶予日数をT065基盤へ渡す。
        boolean noticeDelivered = context.deliveries().stream().anyMatch(d ->
                MissingDocumentDeliveryRule.DOC_TYPE_DISPATCH_NOTICE.equals(d.getDocumentType())
                        && "DELIVERED".equals(d.getDeliveryStatus()));
        int graceDays = configInt(CONFIG_NOTICE_GRACE_DAYS, 3);
        LocalDate noticeDue = profile.getDispatchPeriodStart().plusDays(graceDays);
        if (!noticeDelivered && !today.isBefore(profile.getDispatchPeriodStart())) {
            findings.add(finding(context, CODE_NOTICE, contract.getId(), "DOC:DISPATCH_NOTICE", noticeDue));
        }
        return findings;
    }
}
