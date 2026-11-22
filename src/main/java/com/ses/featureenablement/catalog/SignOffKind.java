package com.ses.featureenablement.catalog;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 署名の種別（requirements §6 の各表。feature-enablement-console design §4.1・§6.3）。code の正本は {@link FeatureEnablementCatalog}。
 *
 * @param code             種別 code（{@code t_feature_sign_off.kind_code}）
 * @param kindClass        類（決定・証拠）。有効期限の上限を決める
 * @param signerRoles      署名者の役割として選べる値（role code）
 * @param referenceRule    参照（決定 ID・文書 ID 等）の規則
 * @param structuredFields 構造化された値の項目名（JSON の key。秘密値を置かない）。無ければ空
 * @param bundleKind       属する署名の組の種類（組に属する種別は1件ずつ確認できない）。無ければ null
 * @param privacy          NF07 の種別か（確認者を privacy-admin に限る。段階 C で有効にする）
 */
public record SignOffKind(String code, SignOffKindClass kindClass, List<String> signerRoles, ReferenceRule referenceRule,
                          List<String> structuredFields, String bundleKind, boolean privacy) {

    public SignOffKind {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kindClass, "kindClass");
        signerRoles = List.copyOf(signerRoles);
        referenceRule = referenceRule == null ? ReferenceRule.GENERIC : referenceRule;
        structuredFields = structuredFields == null ? List.of() : List.copyOf(structuredFields);
    }

    /** 属する署名の組の種類。 */
    public Optional<String> bundle() {
        return Optional.ofNullable(bundleKind);
    }

    /**
     * 参照の規則（design §6.3。種別ごとの細かな規則は各段階の評価器の検査を共通の部品から呼ぶ）。
     */
    public enum ReferenceRule {
        /** 英数字で始まる 4〜128 文字（{@code ._:/#-}）で placeholder の語を含まない（共通の規則）。 */
        GENERIC,
        /** NF01 二者確認の Privacy 署名の参照（{@code LifecycleDualConfirmationReleaseGate.acceptable}）。段階 B で検査を足す。 */
        NF01_DUAL_PRIVACY,
        /** NF07 の決定 ID（{@code PrivacyDecisionBinding} の規則）。段階 C で検査を足す。 */
        NF07_DECISION,
        /** NF06 の admission の決定（{@code ImportAdmissionApproval.evaluate}）。段階 B で検査を足す。 */
        NF06_ADMISSION,
        /** DG-05-IN の決定 ID（{@code DG-05-IN-[0-9]{8}-[0-9]{2}}）。 */
        NF05_DG05_IN,
        /** DG-05-OUT の決定 ID（{@code DG-05-OUT-[0-9]{8}-[0-9]{2}}）。 */
        NF05_DG05_OUT
    }
}
