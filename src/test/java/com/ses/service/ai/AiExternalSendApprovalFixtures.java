package com.ses.service.ai;

import com.ses.config.AiConfig;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * NF08 外部送信承認テスト用の合成fixture。値は全て合成であり、実署名・実provider・実secretではない。
 * テストclassではない（surefire対象外の命名）。
 */
public final class AiExternalSendApprovalFixtures {

    public static final String DECISION_ID = "NF08-EXT-2026-0001";
    public static final String DG08_DECISION_ID = "DG08-2026-0042";
    public static final String NF07_POLICY_ID = "NF07-POL-2026-0007";
    public static final String DPA_REFERENCE = "DPA-GCP-2026-0003";
    public static final String MODEL = "gemini-synthetic-model";
    public static final String HOST = "generativelanguage.googleapis.com";
    public static final List<String> DATA_CATEGORIES = List.of(
            "catalog.queryId", "catalog.catalogVersion", "summary.claimKeys");

    private AiExternalSendApprovalFixtures() {
    }

    /** 現在のJST業務日。 */
    public static LocalDate today() {
        return AiExternalSendApproval.businessDate(Clock.systemUTC());
    }

    /**
     * today基準で有効期間内の完全な承認設定（external-send有効・チェックリスト全true）。
     * approval-digestは決定書への記載を模して、fixtureの承認内容から算出した値を入れる。
     */
    public static AiConfig approvedConfig(LocalDate today) {
        AiConfig config = new AiConfig();
        config.setEnabled(true);
        config.setManagementCopilotEnabled(true);
        config.setProvider("gemini");
        config.setModel(MODEL);
        config.setMaxTokens(1000);
        config.setExternalSendEnabled(true);
        enableChecklist(config.getProductionGates());
        AiConfig.ExternalSendApproval approval = config.getExternalSendApproval();
        approval.setDecisionId(DECISION_ID);
        approval.setDg08DecisionId(DG08_DECISION_ID);
        approval.setNf07PolicyId(NF07_POLICY_ID);
        approval.setDpaReference(DPA_REFERENCE);
        approval.setSignedOn(today.minusDays(1).toString());
        approval.setExpiresOn(today.plusDays(90).toString());
        approval.setProvider("gemini");
        approval.setModel(MODEL);
        approval.setPurposes(new ArrayList<>(List.of(AiGatewayRequest.USE_COPILOT)));
        approval.setDataCategories(new ArrayList<>(DATA_CATEGORIES));
        approval.setDestinationHost(HOST);
        AiConfig.CostLimit cost = approval.getCostLimit();
        cost.setPerRequestYen(50);
        cost.setDailyYen(100);
        cost.setMonthlyYen(1000);
        cost.setInputYenPerThousandTokens(new BigDecimal("1"));
        cost.setOutputYenPerThousandTokens(new BigDecimal("4"));
        cost.setMaxRequestBytes(8192);
        cost.setMaxThinkingTokens(0);
        return resign(config);
    }

    /**
     * 承認内容を変更した後に、決定書のdigestも取り直したこと（再署名）を模す。
     * 内容が不完全でdigestを算出できない場合はdigestを外す。
     */
    public static AiConfig resign(AiConfig config) {
        config.getExternalSendApproval().setApprovalDigest(AiExternalSendApproval.computeDigest(config));
        return config;
    }

    public static void enableChecklist(AiConfig.ProductionGates gates) {
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
    }
}
