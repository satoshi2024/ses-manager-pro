package com.ses.config;

import com.ses.service.ai.AiProductionApprovalGate;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
 */
@Slf4j
@Component
@Profile("prod")
public class AiProductionSafetyValidator {

    /** 時間要因で外部送信を止めて起動したことを示す固定コード（監視のlog検索用）。 */
    public static final String LOG_CODE_STARTED_WITH_EXTERNAL_SEND_STOPPED = "AI_EXTERNAL_SEND_STOPPED_AT_STARTUP";

    private final AiConfig aiConfig;
    private final AiProductionApprovalGate productionApprovalGate;

    @Autowired
    public AiProductionSafetyValidator(AiConfig aiConfig, AiProductionApprovalGate productionApprovalGate) {
        this.aiConfig = aiConfig;
        this.productionApprovalGate = productionApprovalGate;
    }

    public AiProductionSafetyValidator(AiConfig aiConfig) {
        this(aiConfig, new AiProductionApprovalGate(aiConfig));
    }

    @PostConstruct
    void validate() {
        if (aiConfig.isExternalSendEnabled()) {
            AiProductionApprovalGate.AiExternalSendGateStatus decision = productionApprovalGate.externalSendStatus();
            if (decision == null) {
                throw new IllegalStateException("本番のAI external-sendは承認判定を取得できないため起動できません: "
                        + "externalSendStatus");
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
}
