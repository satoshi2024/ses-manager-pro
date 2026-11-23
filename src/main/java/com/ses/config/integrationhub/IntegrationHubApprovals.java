package com.ses.config.integrationhub;

import org.springframework.core.env.Environment;

/**
 * NF05 の承認の値（DG-05 の決定 ID と承認内容の digest。feature-enablement-console design §9.5・review P3-15）。
 *
 * <p>{@link IntegrationHubProductionEnablementPolicy} と {@link IntegrationHubApprovalDigest} は承認の値を properties から読まず、
 * この値を引数で受ける。property の源（dev・test）では {@link #fromProperties} で配備の設定から作り、DB の源では確認済みの署名
 * （{@code NF05_DG05_IN}・{@code NF05_DG05_OUT}）から作る（{@link IntegrationHubEnablementDecision#approvalsFromSignOffs}、起動の前の
 * 読取りが書いた値は {@link #fromBootProperties}）。properties の決定 ID・digest の getter を呼ぶのはこの class だけ
 * （{@code IntegrationHubFlagReadGuardTest}）。値は秘密ではない（決定書に記載された値）。</p>
 *
 * @param inbound  inbound（DG-05-IN）の決定
 * @param outbound outbound（DG-05-OUT）の決定
 */
public record IntegrationHubApprovals(Decision inbound, Decision outbound) {

    /** 起動の前の読取りが写した決定の property（{@code app.feature-enablement.nf05-boot.<方向>.*}）。 */
    public static final String BOOT_PREFIX = "app.feature-enablement.nf05-boot.";

    public IntegrationHubApprovals {
        inbound = inbound == null ? Decision.NONE : inbound;
        outbound = outbound == null ? Decision.NONE : outbound;
    }

    /**
     * 1つの方向の決定。
     *
     * @param decisionId     決定 ID（無ければ null）
     * @param approvalDigest 承認内容の digest（決定書の転記。無ければ null）
     */
    public record Decision(String decisionId, String approvalDigest) {
        public static final Decision NONE = new Decision(null, null);
    }

    /** 承認の無い値（両方向とも無い）。 */
    public static IntegrationHubApprovals none() {
        return new IntegrationHubApprovals(Decision.NONE, Decision.NONE);
    }

    /** 配備の設定（{@code integration.hub.*.approval.*}）から作る（property の源）。 */
    public static IntegrationHubApprovals fromProperties(IntegrationHubExternalApiProperties properties) {
        if (properties == null) {
            return none();
        }
        IntegrationHubExternalApiProperties.InboundApproval in = properties.getPublicApi() == null ? null
                : properties.getPublicApi().getApproval();
        IntegrationHubExternalApiProperties.OutboundApproval out = properties.getExternalTransport() == null ? null
                : properties.getExternalTransport().getApproval();
        return new IntegrationHubApprovals(
                in == null ? Decision.NONE : new Decision(in.getDecisionId(), in.getApprovalDigest()),
                out == null ? Decision.NONE : new Decision(out.getDecisionId(), out.getApprovalDigest()));
    }

    /** 起動の前の読取り（{@code IntegrationHubEnablementBootResolver}）が確認済みの署名から写した値（DB の源）。 */
    public static IntegrationHubApprovals fromBootProperties(Environment environment) {
        if (environment == null) {
            return none();
        }
        return new IntegrationHubApprovals(
                new Decision(environment.getProperty(BOOT_PREFIX + "inbound.decision-id"),
                        environment.getProperty(BOOT_PREFIX + "inbound.approval-digest")),
                new Decision(environment.getProperty(BOOT_PREFIX + "outbound.decision-id"),
                        environment.getProperty(BOOT_PREFIX + "outbound.approval-digest")));
    }
}
