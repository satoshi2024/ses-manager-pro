package com.ses.featureenablement.signoff;

import com.ses.common.exception.BusinessException;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-A06（design §6.3、J-08）: 署名の種別ごとの検査（参照・構造化された値・類の上限・役割）。DG-05 は形式だけを検査し、現在の配備の
 * 設定との一致は評価の時に判定する（review P3-18）。
 */
class FeatureSignOffKindValidationTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    private final FeatureEnablementCatalog catalog = FeatureEnablementTestConfiguration.testCatalog();

    @Test
    void 共通の参照の規則は形式とplaceholderの語を見る() {
        assertThat(SignOffReferenceRules.generic("BO-2026-1101")).isTrue();
        assertThat(SignOffReferenceRules.generic("ADR/2026#12")).isTrue();
        for (String invalid : new String[]{"TODO-1", "TBD2026", "xxxxx", "sample-01", "test-2026", " BO-1", "BO1",
                "-BO-2026", "ＢＯ-2026"}) {
            assertThat(SignOffReferenceRules.generic(invalid)).as(invalid).isFalse();
        }
        assertThat(SignOffReferenceRules.generic(null)).isFalse();
        // NF01 二者確認の Privacy 署名は英字と数字の両方を要する（起動時検査と同じ部品）
        assertThat(SignOffReferenceRules.withLetterAndDigit("PRIV-2026-01")).isTrue();
        assertThat(SignOffReferenceRules.withLetterAndDigit("PRIVACYREF")).isFalse();
        assertThat(SignOffReferenceRules.withLetterAndDigit("2026-0101")).isFalse();
    }

    @Test
    void 種別と役割と参照の検査は外れれば400() {
        assertThat(validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, SIGNED.plusDays(30), Map.of()).code())
                .isEqualTo("NF04_SRE");
        assertRejected(() -> validate("NF99_UNKNOWN", "SRE-2026-11", "SRE", SIGNED, SIGNED.plusDays(30), Map.of()),
                "error.featureEnablement.signOffKindUnknown");
        assertRejected(() -> validate("NF04_SRE", "SRE-2026-11", "BUSINESS_OWNER", SIGNED, SIGNED.plusDays(30), Map.of()),
                "error.featureEnablement.signOffRoleNotAllowed");
        assertRejected(() -> validate("NF04_SRE", "TODO-2026", "SRE", SIGNED, SIGNED.plusDays(30), Map.of()),
                "error.featureEnablement.signOffReferenceInvalid");
        assertRejected(() -> validate("NF01_DUAL_PRIVACY", "PRIVACYREF", "PRIVACY_OWNER", SIGNED, SIGNED.plusDays(30),
                Map.of()), "error.featureEnablement.signOffReferenceInvalid");
        assertThat(validate("NF01_DUAL_PRIVACY", "PRIV-2026-01", "PRIVACY_OWNER", SIGNED, SIGNED.plusDays(30), Map.of())
                .code()).isEqualTo("NF01_DUAL_PRIVACY");
    }

    @Test
    void 有効期間は類の上限まで決定は366日で証拠は1095日() {
        validate("NF04_BUSINESS_OWNER", "BO-2026-1101", "BUSINESS_OWNER", SIGNED, SIGNED.plusDays(366), Map.of());
        BusinessException decision = catchThrowableOfType(() -> validate("NF04_BUSINESS_OWNER", "BO-2026-1101",
                "BUSINESS_OWNER", SIGNED, SIGNED.plusDays(367), Map.of()), BusinessException.class);
        assertThat(decision.getMessageKey()).isEqualTo("error.featureEnablement.signOffValidityTooLong");
        assertThat(decision.getArgs()).containsExactly(366);

        validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, SIGNED.plusDays(1095), Map.of());
        assertRejected(() -> validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, SIGNED.plusDays(1096), Map.of()),
                "error.featureEnablement.signOffValidityTooLong");
        assertRejected(() -> validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, SIGNED, Map.of()),
                "error.featureEnablement.signOffDatesInvalid");
        assertRejected(() -> validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, null, Map.of()),
                "error.featureEnablement.signOffDatesInvalid");
    }

    @Test
    void DG05は決定IDとdigestの形式だけを検査する() {
        SignOffKind inbound = validate("NF05_DG05_IN", "DG-05-IN-20261120-01", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("approvalDigest", DIGEST));
        assertThat(inbound.referenceRule()).isEqualTo(SignOffKind.ReferenceRule.NF05_DG05_IN);
        validate("NF05_DG05_OUT", "DG-05-OUT-20261120-02", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("approvalDigest", DIGEST));

        assertRejected(() -> validate("NF05_DG05_IN", "DG-05-OUT-20261120-01", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("approvalDigest", DIGEST)), "error.featureEnablement.signOffReferenceInvalid");
        assertRejected(() -> validate("NF05_DG05_IN", "DG-05-IN-2026112-01", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("approvalDigest", DIGEST)), "error.featureEnablement.signOffReferenceInvalid");
        assertRejected(() -> validate("NF05_DG05_IN", "DG-05-IN-20261120-01", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("approvalDigest", DIGEST.toUpperCase())), "error.featureEnablement.signOffStructuredInvalid");
        assertRejected(() -> validate("NF05_DG05_IN", "DG-05-IN-20261120-01", "NF05_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of()), "error.featureEnablement.signOffStructuredInvalid");
    }

    @Test
    void 構造化された値は種別が持つ項目だけでvisibleSinceは空かoffset付きのISO8601() {
        validate("NF02_PORTAL_VISIBLE_SINCE_DECISION", "CS-2026-11", "CS_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("visibleSince", "2026-12-01T00:00:00+09:00"));
        validate("NF02_PORTAL_VISIBLE_SINCE_DECISION", "CS-2026-11", "CS_OWNER", SIGNED, SIGNED.plusDays(300),
                Map.of("visibleSince", ""));
        assertRejected(() -> validate("NF02_PORTAL_VISIBLE_SINCE_DECISION", "CS-2026-11", "CS_OWNER", SIGNED,
                SIGNED.plusDays(300), Map.of("visibleSince", "2026-12-01")),
                "error.featureEnablement.signOffStructuredInvalid");
        assertRejected(() -> validate("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, SIGNED.plusDays(30),
                Map.of("approvalDigest", DIGEST)), "error.featureEnablement.signOffStructuredInvalid");
    }

    /**
     * FE-B02（FE-AC-004-06）: NF06 の admission は構造化された値を起動時検査と同じ {@code ImportAdmissionApproval.evaluate} で検査し、
     * system が正規形から計算した digest と入力した digest の不一致・構造的な不足は 400（違反の code だけを返す）。golden vector
     * （{@code c5dc5f03…}）の値は通る。署名日が未来でも登録はできる（時間要因は確認の時の検査）。
     */
    @Test
    void NF06のadmissionは正規形から計算したdigestと一致し構造的な不足が無い時だけ通る() {
        LocalDate signed = LocalDate.of(2026, 12, 1);
        LocalDate expires = LocalDate.of(2027, 11, 30);
        Map<String, String> golden = Map.of("decisionId", "DO-NF06-2026-001", "tenantId", "default",
                "sourceCodes", "CRM_2,crm-2,Crm,crm2,crm",
                "approvalDigest", "c5dc5f03efdb4fd22c23cdf86c7d248f22af96d187880a87f07664a97b173550");
        assertThat(validate("NF06_ADMISSION", "DO-NF06-2026-001", "DATA_OWNER", signed, expires, golden).code())
                .isEqualTo("NF06_ADMISSION");

        Map<String, String> tampered = new java.util.HashMap<>(golden);
        tampered.put("sourceCodes", "CRM_2,crm-2,Crm,crm2,crm,secret-extra");
        BusinessException mismatch = catchThrowableOfType(() -> validate("NF06_ADMISSION", "DO-NF06-2026-001",
                "DATA_OWNER", signed, expires, tampered), BusinessException.class);
        assertThat(mismatch.getCode()).isEqualTo(400);
        assertThat(mismatch.getMessageKey()).isEqualTo("error.featureEnablement.admissionInvalid");
        assertThat(String.valueOf(mismatch.getArgs()[0])).isEqualTo("approvalDigest.mismatch")
                .doesNotContain("secret-extra");

        Map<String, String> placeholder = new java.util.HashMap<>(golden);
        placeholder.put("decisionId", "DO-TBD-0001");
        assertRejected(() -> validate("NF06_ADMISSION", "DO-NF06-2026-001", "DATA_OWNER", signed, expires, placeholder),
                "error.featureEnablement.admissionInvalid");
        assertRejected(() -> validate("NF06_ADMISSION", "DO-NF06-2026-001", "DATA_OWNER", signed, expires, Map.of()),
                "error.featureEnablement.admissionInvalid");
    }

    private SignOffKind validate(String kind, String reference, String role, LocalDate signedOn, LocalDate expiresOn,
                                 Map<String, String> values) {
        return FeatureSignOffKindValidator.validate(catalog,
                new FeatureSignOffKindValidator.Input(kind, reference, role, signedOn, expiresOn, values));
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException rejected = catchThrowableOfType(call, BusinessException.class);
        assertThat(rejected).as(messageKey).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(400);
        assertThat(rejected.getMessageKey()).isEqualTo(messageKey);
    }
}
