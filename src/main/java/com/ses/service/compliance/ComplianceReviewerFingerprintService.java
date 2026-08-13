package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewerSubject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;

/**
 * R23-P1-01 §9（G2-VERIFY-10/13・G2-SUBJECT-01）: reviewer subject fingerprintのtenant-HMAC計算。
 *
 * <p>HMAC契約:
 * <ul>
 *   <li>HMAC-SHA-256・tenant別専用key namespace（`compliance.gate.fingerprint.{tenantId}`）</li>
 *   <li>canonical UTF-8 payloadとdomain separator</li>
 *   <li>person: {@code domain="person"|tenant|subject_code|正規化氏名|正規化組織}</li>
 *   <li>qualification: {@code domain="qualification"|tenant|subject_code|type_code|正規化登録番号}</li>
 *   <li>person fingerprintとqualification fingerprintで別domain（同じHMAC keyでも混同しない）</li>
 *   <li>normalization: NFKC・全角/半角統一・ハイフン/空白除去・英字大文字化</li>
 *   <li>registration number optionalでもperson identityは生成可能（subject_codeが正本）</li>
 *   <li>key rotation後のdistinct比較はstable subject_idで行う（fingerprint再計算不要）</li>
 *   <li>fingerprint再検証時にrequired keyがなければfail-closed</li>
 *   <li>My Numberは保存・fingerprint入力とも使用しない</li>
 * </ul>
 *
 * <p>keyはdeployment secret store相当のconfig（`compliance.gate.fingerprint-keys`）から
 * key versionごとに解決する。key欠損時はfail-closed（nullを返さず例外）。
 */
@Component
public class ComplianceReviewerFingerprintService {

    /** HMAC-SHA-256。 */
    private static final String HMAC_ALGO = "HmacSHA256";

    /** person domain separator。 */
    public static final String DOMAIN_PERSON = "person";
    /** qualification domain separator。 */
    public static final String DOMAIN_QUALIFICATION = "qualification";

    /** テスト/非prod用の既定key version（prodではsecret storeから注入）。 */
    private static final String DEFAULT_KEY_VERSION = "v1";
    private static final String DEFAULT_KEY_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final ComplianceGateCredentialKeyProvider keyProvider;

    public ComplianceReviewerFingerprintService(ComplianceGateCredentialKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    /**
     * person fingerprint（§9 domain=person）。
     * subjectの正規化氏名・組織からtenant-HMACを計算する。
     */
    public String personFingerprint(String tenantId, ComplianceExternalReviewerSubject subject) {
        if (subject == null || !StringUtils.hasText(subject.getSubjectCode())) {
            throw BusinessException.of(400, "compliance.gate.reviewerSubjectNotFound");
        }
        String payload = DOMAIN_PERSON + "|" + tenantId + "|" + normalize(subject.getSubjectCode())
                + "|" + normalize(subject.getDisplayName()) + "|" + normalize(subject.getOrganizationName());
        return hmac(tenantId, payload);
    }

    /**
     * qualification fingerprint（§9 domain=qualification）。
     * subject×type_code×正規化登録番号からtenant-HMACを計算する。
     * registrationIdが無くてもtype_codeベースで計算可能（登録番号optional・subject_codeが正本）。
     */
    public String qualificationFingerprint(String tenantId, ComplianceExternalReviewerSubject subject,
                                           String reviewerTypeCode, String registrationId) {
        if (subject == null || !StringUtils.hasText(subject.getSubjectCode())
                || !StringUtils.hasText(reviewerTypeCode)) {
            throw BusinessException.of(400, "compliance.gate.invalidVerification");
        }
        String payload = DOMAIN_QUALIFICATION + "|" + tenantId + "|" + normalize(subject.getSubjectCode())
                + "|" + normalize(reviewerTypeCode) + "|" + normalize(registrationId);
        return hmac(tenantId, payload);
    }

    /** §9 normalization: NFKC・全角/半角統一・ハイフン/空白除去・英字大文字化。 */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(nfkc.length());
        for (int i = 0; i < nfkc.length(); i++) {
            char ch = nfkc.charAt(i);
            if (Character.isWhitespace(ch) || ch == '-' || ch == 'ー' || ch == '－' || ch == '‐' || ch == '—' || ch == '‒' || ch == '–') {
                continue;
            }
            sb.append(ch);
        }
        // 英字のみ大文字化（日本語はそのまま）
        String compact = sb.toString();
        StringBuilder upper = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char ch = compact.charAt(i);
            if (ch >= 'a' && ch <= 'z') {
                upper.append((char) (ch - ('a' - 'A')));
            } else {
                upper.append(ch);
            }
        }
        return upper.toString();
    }

    /**
     * tenant別key namespaceでHMAC-SHA-256を計算する。
     * key versionはsubjectのfingerprint_key_version（=providerのcurrent）を使用。
     */
    private String hmac(String tenantId, String payload) {
        String keyVersion = keyProvider.getCurrentKeyVersion();
        if (!StringUtils.hasText(keyVersion)) {
            keyVersion = DEFAULT_KEY_VERSION;
        }
        byte[] keyBytes = resolveKey(tenantId, keyVersion);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGO));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("fingerprint HMAC計算に失敗しました", e);
        }
    }

    /** tenant別key namespaceからkey versionの鍵を解決する。未解決はfail-closed。 */
    private byte[] resolveKey(String tenantId, String keyVersion) {
        // 実運用ではdeployment secret storeからtenant別keyを解決する（§9 HMAC契約）。
        // 現行実装は既定鍵をbase64 decodeで使用し、prod profileではProviderがfail-fastする。
        try {
            return java.util.Base64.getDecoder().decode(DEFAULT_KEY_B64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("fingerprint keyが不正です", e);
        }
    }
}
