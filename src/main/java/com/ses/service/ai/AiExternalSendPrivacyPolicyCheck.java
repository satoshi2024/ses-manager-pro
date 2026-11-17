package com.ses.service.ai;

import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.foundation.PolicyFailureReason;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * NF07-NF08-LINK: 承認済み外部送信の送信ごとに、NF07 の policy が現行で有効かを NF07 の内部判定 port
 * （{@link PolicyEnforcementPort}）で確かめる（production-bridge-phased-plan.md §8、external-send-approval-gate.md §1.1）。
 *
 * <p>key はコードの定数（module {@value #MODULE_CODE}・data element {@value #DATA_ELEMENT_ID}・action {@value #ACTION}・
 * provider は {@link #POLICY_PROVIDER_IDS}）、期待 policy ID は承認設定の {@code nf07-policy-id}、tenant は送信の実行 tenant。
 * NF07 の公開スイッチ OFF・UNKNOWN・EXPIRED・REVOKED・DRIFT・policy ID の不一致、port を取得できない・例外は全て拒否する
 * （fail-closed）。結果を cache しない（policy の失効・期限切れは NF08 の設定を変えなくても次の送信から止まる）。
 *
 * <p>拒否は固定コードの WARN log（{@value #LOG_CODE_DENIED}）と metric（{@value #DENIED_METRIC}、tag は stage と reason）だけを
 * 出す。tenant・policy ID・決定ID・PII は log と metric に出さない。
 */
@Slf4j
public final class AiExternalSendPrivacyPolicyCheck {

    /** NF07 policy key の module（計画 §8 の手順2）。 */
    public static final String MODULE_CODE = "NF08_AI_SUMMARY";
    /** NF07 policy key の data element（計画 §8 の手順2。本番の正式な値は F2/REG で確定する）。 */
    public static final String DATA_ELEMENT_ID = "AI_PROMPT_LOG";
    /** NF07 policy key の action。 */
    public static final String ACTION = "EXTERNAL_DISPATCH";
    /** NF08 の provider 名（承認設定の provider、小文字）から NF07 policy key の provider ID への対応。 */
    public static final Map<String, String> POLICY_PROVIDER_IDS = Map.of("gemini", "gemini-adapter");

    /** NF07 policy を確認できないため送信しない（BusinessException の message key）。 */
    public static final String REASON_POLICY_NOT_ACTIVE = "AI_EXTERNAL_SEND_PRIVACY_POLICY_NOT_ACTIVE";
    /** 拒否の件数（Prometheus では {@code ses_ai_external_send_privacy_policy_denied_total}）。 */
    public static final String DENIED_METRIC = "ses.ai.external.send.privacy.policy.denied";
    /** 拒否の WARN log の固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_DENIED = "AI_EXTERNAL_SEND_PRIVACY_POLICY_DENIED";

    /** 判定した場所（gateway の送信前検査か、adapter の送信直前の再確認か）。 */
    public enum Stage {
        GATEWAY,
        ADAPTER
    }

    private static final AiExternalSendPrivacyPolicyCheck UNAVAILABLE =
            new AiExternalSendPrivacyPolicyCheck(() -> null, () -> null);

    private final Supplier<PolicyEnforcementPort> port;
    private final Supplier<MeterRegistry> registry;

    /**
     * @param port     NF07 の内部判定 port。取得できなければ（null・例外）拒否する
     * @param registry metric の登録先。取得できなければ metric を出さない（判定には影響しない）
     */
    public AiExternalSendPrivacyPolicyCheck(Supplier<PolicyEnforcementPort> port, Supplier<MeterRegistry> registry) {
        this.port = port == null ? () -> null : port;
        this.registry = registry == null ? () -> null : registry;
    }

    /** port を持たない判定（常に拒否）。手動構築の gate の既定値。 */
    public static AiExternalSendPrivacyPolicyCheck unavailable() {
        return UNAVAILABLE;
    }

    /** 判定する。拒否なら固定コードの WARN log と metric を出す。 */
    public PolicyEnforcementReason evaluate(String tenantId, AiExternalSendApproval.Snapshot approval, Stage stage) {
        PolicyEnforcementDecision decision = decide(tenantId, approval);
        if (!decision.permitted()) {
            recordDenied(stage, decision);
        }
        return decision.reason();
    }

    /** 判定を満たしたか。 */
    public boolean permits(String tenantId, AiExternalSendApproval.Snapshot approval, Stage stage) {
        return evaluate(tenantId, approval, stage) == PolicyEnforcementReason.PERMITTED;
    }

    private PolicyEnforcementDecision decide(String tenantId, AiExternalSendApproval.Snapshot approval) {
        if (approval == null || tenantId == null || tenantId.isBlank()) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }
        String providerId = approval.provider() == null ? null : POLICY_PROVIDER_IDS.get(approval.provider());
        if (providerId == null || approval.nf07PolicyId() == null || approval.nf07PolicyId().isBlank()) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.REQUEST_INVALID, PolicyFailureReason.NONE);
        }
        PolicyEnforcementPort enforcement;
        try {
            enforcement = port.get();
        } catch (RuntimeException unavailable) {
            enforcement = null;
        }
        if (enforcement == null) {
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE,
                    PolicyFailureReason.NONE);
        }
        try {
            PolicyEnforcementDecision decision = enforcement.check(new PolicyEnforcementRequest(tenantId,
                    MODULE_CODE, DATA_ELEMENT_ID, ACTION, providerId, approval.nf07PolicyId()));
            return decision == null
                    ? PolicyEnforcementDecision.deny(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE,
                            PolicyFailureReason.NONE)
                    : decision;
        } catch (RuntimeException unexpected) {
            // port の契約（例外を投げない）に反した場合も送信しない。
            return PolicyEnforcementDecision.deny(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE,
                    PolicyFailureReason.NONE);
        }
    }

    private void recordDenied(Stage stage, PolicyEnforcementDecision decision) {
        String stageName = stage == null ? "UNKNOWN" : stage.name();
        log.warn("{}: NF07のpolicyを確認できないためAI外部送信を拒否します: stage={} reason={} detail={}",
                LOG_CODE_DENIED, stageName, decision.reason().name(), decision.detail().name());
        try {
            MeterRegistry meters = registry.get();
            if (meters != null) {
                meters.counter(DENIED_METRIC, "stage", stageName, "reason", decision.reason().name()).increment();
            }
        } catch (RuntimeException ignored) {
            // metric の失敗は判定（拒否）を変えない。
        }
    }
}
