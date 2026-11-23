package com.ses.featureenablement.signoff;

import com.ses.common.exception.BusinessException;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.importcenter.metadata.ImportAdmissionApproval;
import com.ses.importcenter.metadata.ImportAdmissionApprovalProperties;
import com.ses.importcenter.metadata.ImportAdmissionApprovalSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 署名の種別ごとの検査（feature-enablement-console design §6.3。登録の時と確認の時に使う）。
 *
 * <ul>
 *   <li>種別は目録にあるものだけ、署名者の役割は種別が許す値だけ。</li>
 *   <li>参照: 共通の規則（{@link SignOffReferenceRules#generic}）。NF01 二者確認の Privacy 署名は起動時検査と同じ規則
 *       （英字と数字を含む）、DG-05 は決定 ID の形式。NF07 の決定（段階 C）の細かな規則は段階 C の評価器が足し、それまでは共通の
 *       規則で受ける。NF06 の admission は参照を共通の規則で受け、構造化された値を {@link #validateAdmission} で検査する（FE-B02）。</li>
 *   <li>構造化された値: 種別が持つ項目だけ。DG-05 の {@code approvalDigest} は小文字 hex 64 桁（形式だけ。現在の配備の設定との
 *       一致は評価の時に判定する。review P3-18）、{@code visibleSince} は空か offset 付き ISO-8601。</li>
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
        if (kind.referenceRule() == SignOffKind.ReferenceRule.NF06_ADMISSION) {
            validateAdmission(input);
        }
        return kind;
    }

    /**
     * NF06 の admission の決定（FE-B02。requirements FE-RQ-004・FE-AC-004-06）: 構造化された値と署名日・失効日を
     * {@link ImportAdmissionApprovalProperties} に写し、起動時検査と同じ {@link ImportAdmissionApproval#evaluate} の構造的な不足
     * （未設定・形式・placeholder・source code・有効期間の上限・system が正規形から計算した digest と入力した digest の不一致）が
     * あれば 400（違反の固定 code だけを返す）。時間要因（署名日前・失効）は登録・確認の他の検査が扱う（署名日の当日で評価する）。
     */
    static void validateAdmission(Input input) {
        ImportAdmissionApprovalProperties properties = new ImportAdmissionApprovalProperties();
        Map<String, String> values = input.structuredValues();
        properties.setDecisionId(values.get("decisionId"));
        properties.setTenantId(values.get("tenantId"));
        properties.setSignedOn(input.signedOn() == null ? null : input.signedOn().toString());
        properties.setExpiresOn(input.expiresOn() == null ? null : input.expiresOn().toString());
        properties.setSourceCodes(ImportAdmissionApprovalSource.sourceCodes(values.get("sourceCodes")));
        properties.setApprovalDigest(values.get("approvalDigest"));
        List<String> structural = ImportAdmissionApproval.structuralViolations(
                ImportAdmissionApproval.evaluate(properties, input.signedOn()));
        if (!structural.isEmpty()) {
            throw BusinessException.of(400, "error.featureEnablement.admissionInvalid", String.join(",", structural));
        }
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
            case GENERIC, NF07_DECISION, NF06_ADMISSION -> SignOffReferenceRules.generic(reference);
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
