package com.ses.privacy.production;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NF07 公開スイッチの決定ID束縛（{@code privacy.approval.*}）。production-bridge-phased-plan.md §3.1.1・§5.1・§5.2。
 *
 * <p>値は署名済み決定書（D-01 正式 Privacy Owner、D-06 DSAR SOP、D-09 本番 flag 開放）の転記で、秘密値を置かない。
 * 既定は全て未設定。評価は {@link PrivacyDecisionBinding} が行い（I/O・状態を持たない）、prod で NF07 の flag を
 * 1つでも ON にした起動では {@link PrivacyProductionConfigurationValidator} が完全性を検査する。
 * 決定IDは「人が署名した」ことを system に伝える入力であって承認の証明ではない。値は署名後に人が設定し、
 * AI・実装者は設定しない。値は起動時に一度だけ bind され、実行中に変わらない。
 *
 * <p>D-02〜D-05・D-07・D-08 は、それを前提にする capability を追加する batch（REG・A1H・B1・B2 等）で足す。
 */
@Data
@Component
@ConfigurationProperties(prefix = "privacy.approval")
public class PrivacyApprovalProperties {

    /** 承認対象の tenant ID。権限評価に使う設定 tenant（OIDC tenant-id、未設定なら default）と完全一致しなければ拒否する。 */
    private String tenantId;

    /** D-09 で開放を承認した capability（{@code retention} / {@code dsar}）。比較は小文字化した値で行う。 */
    private List<String> capabilities = new ArrayList<>();

    /** 決定書に記載された束縛内容の SHA-256（小文字hex 64桁）。現行設定からの再計算値と一致しなければ拒否する。 */
    private String approvalDigest;

    /** D-01 正式 Privacy Owner。全 capability の前提。 */
    private DecisionReference d01 = new DecisionReference();

    /** D-06 DSAR SOP。{@code dsar} の前提。 */
    private DecisionReference d06 = new DecisionReference();

    /** D-09 本番 flag 開放。全 capability の前提。 */
    private DecisionReference d09 = new DecisionReference();

    /** 決定ごとの設定。未対応の決定は null。 */
    public DecisionReference reference(PrivacyDecisionBinding.Decision decision) {
        if (decision == null) {
            return null;
        }
        return switch (decision) {
            case D01 -> d01;
            case D06 -> d06;
            case D09 -> d09;
        };
    }

    /** 1件の署名済み決定の転記。 */
    @Data
    public static class DecisionReference {
        /** 決定ID（例: {@code D01-NF07-2026-001}）。 */
        private String decisionId;
        /** 署名日（ISO-8601、JST業務日）。 */
        private String signedOn;
        /** 失効日（ISO-8601、JST業務日。この日以降は拒否）。 */
        private String expiresOn;
    }
}
