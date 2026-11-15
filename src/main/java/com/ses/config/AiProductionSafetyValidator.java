package com.ses.config;

import com.ses.service.ai.AiProductionApprovalGate;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 本番では明示承認のないmanagement Copilotと外部送信を起動時に拒否する。
 * <p>外部送信は固定拒否ではなく「署名済み承認設定」で開く。承認設定（決定ID・model・用途・data category・宛先・
 * 費用上限・承認digest）とチェックリストに構造的な欠落・不一致があれば起動を失敗させる（fail-fast）。
 * 設定誤りを最初の利用者requestまで潜伏させず、deploy時点で検出するためである。</p>
 * <p>一方、失効日の経過・署名日前のように時間の経過だけで生じる不足では起動を止めない。外部送信だけを停止
 * （gatewayは固定deny-allのまま）し、WARN logと監視metric（{@code AiExternalSendApprovalMonitor}）で知らせる。
 * 承認の失効で、AIと無関係な機能まで再起動のたびに使えなくなる事態を避けるためである。
 * 費用累計・requestごとの用途/宛先・承認digestは、gatewayが呼出し時にも再検査する。</p>
 * <p><b>B2c-1〜B2c-2の間（計画 C-2 / R-5）</b>: 外部socketを commit済みclaimのpermitからだけ開く入口と、gatewayの
 * 永続費用ledgerへの接続（B2c-2）が完成し独立Reviewを通るまで、prodは external-send=true 自体を構造的な不足
 * （{@link #GATE_PERMIT_DISPATCH_NOT_WIRED}）として起動拒否する。この状態は設定値では変えられず、B2c-2のcommitで
 * {@link #PERMIT_ONLY_DISPATCH_WIRED} を変更したときだけ解除される。解除後も費用ledgerが {@code persistent} でなければ
 * 起動拒否する（{@link #GATE_COST_LEDGER_NOT_PERSISTENT}）。</p>
 */
@Slf4j
@Component
@Profile("prod")
public class AiProductionSafetyValidator {

    /** 時間要因で外部送信を止めて起動したことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STARTED_WITH_EXTERNAL_SEND_STOPPED = "AI_EXTERNAL_SEND_STOPPED_AT_STARTUP";

    /** permit-only送信入口とgatewayの永続ledger接続（B2c-2）が未完成であることを示す起動拒否コード。 */
    public static final String GATE_PERMIT_DISPATCH_NOT_WIRED = "dispatch.permitOnlyNotWired";

    /** 費用ledgerが永続方式でないことを示す起動拒否コード。 */
    public static final String GATE_COST_LEDGER_NOT_PERSISTENT = "costLedger.notPersistent";

    /**
     * B2c-2（permit-only送信入口とgatewayの永続費用ledger接続）の完了と独立Reviewまでfalse。
     * 設定・環境変数からは変更できない。falseの間、prodは external-send=true を起動拒否する。
     */
    static final boolean PERMIT_ONLY_DISPATCH_WIRED = false;

    private final AiConfig aiConfig;
    private final AiProductionApprovalGate productionApprovalGate;
    private final boolean permitOnlyDispatchWired;

    @Autowired
    public AiProductionSafetyValidator(AiConfig aiConfig, AiProductionApprovalGate productionApprovalGate) {
        this(aiConfig, productionApprovalGate, PERMIT_ONLY_DISPATCH_WIRED);
    }

    /** B2c-2完了後の判定をtestで固定するための構築。本番のbean生成には使わない。 */
    AiProductionSafetyValidator(AiConfig aiConfig, AiProductionApprovalGate productionApprovalGate,
                                boolean permitOnlyDispatchWired) {
        this.aiConfig = aiConfig;
        this.productionApprovalGate = productionApprovalGate;
        this.permitOnlyDispatchWired = permitOnlyDispatchWired;
    }

    public AiProductionSafetyValidator(AiConfig aiConfig) {
        this(aiConfig, new AiProductionApprovalGate(aiConfig));
    }

    @PostConstruct
    void validate() {
        try {
            new com.ses.service.ai.AiProviderRegistry().requireRegistered(aiConfig.getProvider());
        } catch (com.ses.common.exception.BusinessException ex) {
            throw new IllegalStateException("本番のAI providerが未登録です: AI_PROVIDER_NOT_REGISTERED");
        }
        if (aiConfig.isExternalSendEnabled()) {
            AiProductionApprovalGate.AiExternalSendGateStatus decision = productionApprovalGate.externalSendStatus();
            if (decision == null) {
                throw new IllegalStateException("本番のAI external-sendは承認判定を取得できないため起動できません: "
                        + "externalSendStatus");
            }
            List<String> dispatchGates = dispatchReadinessGates();
            if (!dispatchGates.isEmpty()) {
                // 承認設定が完全でもここで止める。診断のため承認側の不足コードも併記する（値は出さない）。
                List<String> reported = new ArrayList<>(dispatchGates);
                if (!decision.approved()) {
                    reported.addAll(decision.missingGates());
                }
                throw new IllegalStateException("本番のAI external-sendはpermit-only送信と永続費用ledgerの接続が"
                        + "完了していないため起動できません: " + String.join(",", reported));
            }
            if (!decision.approved()) {
                List<String> blocking = decision.startupBlockingGates();
                if (!blocking.isEmpty()) {
                    throw new IllegalStateException("本番のAI external-sendは署名済み承認設定が完全でないため起動できません: "
                            + String.join(",", decision.missingGates()));
                }
                // 失効・署名日前だけ: 外部送信は停止したまま起動する（gatewayは起動時に承認を固定しないため固定deny-all）。
                log.warn("{}: AI外部送信の承認が有効期間外のため外部送信を停止して起動します。再承認後に再起動してください: gates={}",
                        LOG_CODE_STARTED_WITH_EXTERNAL_SEND_STOPPED, String.join(",", decision.missingGates()));
            }
        }
        if (aiConfig.isManagementCopilotEnabled() && !productionApprovalGate.status().approved()) {
            throw new IllegalStateException("本番のmanagement Copilotの独立承認gateが未完了です");
        }
    }

    /** permit-only送信と永続費用ledgerの準備状況。空でなければ external-send=true の起動を拒否する。 */
    private List<String> dispatchReadinessGates() {
        List<String> gates = new ArrayList<>();
        if (!permitOnlyDispatchWired) {
            gates.add(GATE_PERMIT_DISPATCH_NOT_WIRED);
        }
        // 空白付き・大文字等の近似値も永続方式とみなさない（Bean条件・設定値の解釈差を作らない）。
        if (!AiConfig.COST_LEDGER_PERSISTENT.equals(aiConfig.getExternalSendCostLedger())) {
            gates.add(GATE_COST_LEDGER_NOT_PERSISTENT);
        }
        return gates;
    }
}
