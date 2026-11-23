package com.ses.featureenablement.signoff;

import com.ses.common.exception.BusinessException;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.privacy.production.PrivacyApprovalSource;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.dsar.PrivacyDsarDecisions;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 署名の種別ごとの検査（feature-enablement-console design §6.3。登録の時と確認の時に使う）。
 *
 * <ul>
 *   <li>種別は目録にあるものだけ、署名者の役割は種別が許す値だけ。</li>
 *   <li>参照: 共通の規則（{@link SignOffReferenceRules#generic}）。NF01 二者確認の Privacy 署名は起動時検査と同じ規則
 *       （英字と数字を含む）、DG-05 は決定 ID の形式、NF07 の決定は {@code PrivacyDecisionBinding} の決定 ID の規則（形式と placeholder の
 *       拒否。FE-C01）。NF06 の admission（段階 B）の細かな規則は段階 B の評価器が足し、それまでは共通の規則で受ける。</li>
 *   <li>構造化された値: 種別が持つ項目だけ。DG-05 の {@code approvalDigest} は小文字 hex 64 桁（形式だけ。現在の配備の設定との
 *       一致は評価の時に判定する。review P3-18）、{@code visibleSince} は空か offset 付き ISO-8601。NF07 の D-09 は {@code tenantId}・
 *       {@code capabilities}（{@code retention} / {@code dsar}）・{@code approvalDigest}（小文字 hex 64 桁）、D-06 は {@code dueDays}
 *       （1〜366）・{@code packageMaxAge}（1時間〜366日）を必須とする（FE-C01。digest と組の中の整合は組の確認の時に確かめる）。</li>
 *   <li>日付: 失効日は署名日より後、期間は類の上限（決定 366 日・証拠 1,095 日。J-08）以内。</li>
 * </ul>
 * 外れれば 400（値そのものは返さない）。
 */
public final class FeatureSignOffKindValidator {

    /** DG-05-IN の決定 ID（{@code IntegrationHubProductionEnablementPolicy} と同じ形）。 */
    public static final Pattern DG05_IN = Pattern.compile("DG-05-IN-[0-9]{8}-[0-9]{2}");
    /** DG-05-OUT の決定 ID。 */
    public static final Pattern DG05_OUT = Pattern.compile("DG-05-OUT-[0-9]{8}-[0-9]{2}");
    /** digest の形（小文字 hex 64 桁）。 */
    public static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    /** 構造化された値の1項目の長さの上限。 */
    public static final int STRUCTURED_VALUE_MAX = 500;

    private FeatureSignOffKindValidator() {
    }

    /** 検査の入力（文書と氏名を含めない）。 */
    public record Input(String kindCode, String reference, String signerRole, LocalDate signedOn, LocalDate expiresOn,
                        Map<String, String> structuredValues) {

        public Input {
            structuredValues = structuredValues == null ? Map.of() : Map.copyOf(structuredValues);
        }
    }

    /** 検査して種別を返す（外れれば 400）。 */
    public static SignOffKind validate(FeatureEnablementCatalog catalog, Input input) {
        SignOffKind kind = input.kindCode() == null ? null : catalog.kind(input.kindCode()).orElse(null);
        if (kind == null) {
            throw BusinessException.of(400, "error.featureEnablement.signOffKindUnknown");
        }
        if (input.signerRole() == null || !kind.signerRoles().contains(input.signerRole())) {
            throw BusinessException.of(400, "error.featureEnablement.signOffRoleNotAllowed");
        }
        if (!referenceAcceptable(kind, input.reference())) {
            throw BusinessException.of(400, "error.featureEnablement.signOffReferenceInvalid");
        }
        validateStructured(kind, input.structuredValues());
        validateDates(kind, input.signedOn(), input.expiresOn());
        return kind;
    }

    /** 種別の参照の規則に合うか。 */
    public static boolean referenceAcceptable(SignOffKind kind, String reference) {
        if (reference == null) {
            return false;
        }
        return switch (kind.referenceRule()) {
            case NF01_DUAL_PRIVACY -> SignOffReferenceRules.withLetterAndDigit(reference);
            case NF05_DG05_IN -> DG05_IN.matcher(reference).matches();
            case NF05_DG05_OUT -> DG05_OUT.matcher(reference).matches();
            case NF07_DECISION -> PrivacyDecisionBinding.isAcceptableDecisionId(reference);
            case GENERIC, NF06_ADMISSION -> SignOffReferenceRules.generic(reference);
        };
    }

    /** 失効日は署名日より後で、期間は類の上限以内。 */
    public static void validateDates(SignOffKind kind, LocalDate signedOn, LocalDate expiresOn) {
        if (signedOn == null || expiresOn == null || !expiresOn.isAfter(signedOn)) {
            throw BusinessException.of(400, "error.featureEnablement.signOffDatesInvalid");
        }
        int max = kind.kindClass().maxValidityDays();
        if (ChronoUnit.DAYS.between(signedOn, expiresOn) > max) {
            throw BusinessException.of(400, "error.featureEnablement.signOffValidityTooLong", max);
        }
    }

    /** NF07 の D-09・D-06 の構造化された値（必須。値は返さない）。 */
    private static void validateNf07(SignOffKind kind, Map<String, String> values) {
        if (kind.structuredFields().contains("approvalDigest")) {
            String digest = values.get("approvalDigest");
            if (digest == null || !DIGEST.matcher(digest).matches()) {
                throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "approvalDigest");
            }
        }
        if (kind.structuredFields().contains("tenantId")
                && !PrivacyDecisionBinding.isAcceptableTenantId(values.get("tenantId"))) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "tenantId");
        }
        if (kind.structuredFields().contains("capabilities") && PrivacyDecisionBinding.parseCapabilities(
                PrivacyApprovalSource.splitCapabilities(values.get("capabilities"))) == null) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "capabilities");
        }
        if (kind.structuredFields().contains("dueDays")
                && !PrivacyDsarDecisions.isValidDueDays(PrivacyApprovalSource.parseDueDays(values.get("dueDays")))) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "dueDays");
        }
        if (kind.structuredFields().contains("packageMaxAge") && !PrivacyDsarDecisions.isValidPackageMaxAge(
                PrivacyApprovalSource.parsePackageMaxAge(values.get("packageMaxAge")))) {
            throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "packageMaxAge");
        }
    }

    private static void validateStructured(SignOffKind kind, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!kind.structuredFields().contains(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().length() > STRUCTURED_VALUE_MAX) {
                throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", entry.getKey());
            }
        }
        if (kind.structuredFields().contains("approvalDigest")
                && (kind.referenceRule() == SignOffKind.ReferenceRule.NF05_DG05_IN
                || kind.referenceRule() == SignOffKind.ReferenceRule.NF05_DG05_OUT)) {
            String digest = values.get("approvalDigest");
            if (digest == null || !DIGEST.matcher(digest).matches()) {
                throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "approvalDigest");
            }
        }
        if (kind.referenceRule() == SignOffKind.ReferenceRule.NF07_DECISION) {
            validateNf07(kind, values);
        }
        String visibleSince = values.get("visibleSince");
        if (visibleSince != null && !visibleSince.isEmpty()) {
            try {
                OffsetDateTime.parse(visibleSince);
            } catch (DateTimeParseException invalid) {
                throw BusinessException.of(400, "error.featureEnablement.signOffStructuredInvalid", "visibleSince");
            }
        }
    }
}
