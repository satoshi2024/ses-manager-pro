package com.ses.privacy.production.retention.policy;

import com.ses.service.AuditLogService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * policy の登録・有効化の required 監査（NF07-REG）。
 *
 * <p>本文（URI）に入れるのは行 ID・policy ID・revision・digest の先頭・authority・key・data element・action・provider と、拒否の
 * 固定 code だけで、envelope の bytes・署名・法的な値（保持期間・起算 trigger・処分方式）・subject 識別子は入れない
 * （{@code /api/audit-logs} は privacy-admin 以外の管理者も見られるため）。1行は {@value #MAX_URI} 文字以内。登録・有効化の監査は
 * 状態の変更と同じ transaction で書き、記録できなければ例外を返して変更も失敗させる。
 */
final class PrivacyPolicyAudit {

    /** 監査の method 列の値（{@code t_audit_log.method} は {@code VARCHAR(10)}）。 */
    static final String METHOD = "PRIV_POL";
    static final String URI_PREFIX = "/api/privacy/policies#";
    private static final int MAX_URI = 500;

    /** 署名済み envelope を検証して CANDIDATE として保存した。 */
    static final String REGISTERED = "REGISTERED";
    /** CANDIDATE を current にした（旧 current の SUPERSEDED と同じ transaction）。 */
    static final String ACTIVATED = "ACTIVATED";
    /** 有効化で旧 current を SUPERSEDED にした。 */
    static final String SUPERSEDED = "SUPERSEDED";
    /** 検証・職務分離・状態の拒否（理由は固定 code）。 */
    static final String REJECTED = "REJECTED";

    private final AuditLogService auditLogService;

    PrivacyPolicyAudit(AuditLogService auditLogService) {
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /** 監査本文の項目（挿入順。値が null の項目は入れない）。 */
    static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                fields.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return fields;
    }

    /** required 監査を1件記録する（失敗は例外）。 */
    void record(String username, String event, Map<String, String> fields, int status, boolean success) {
        auditLogService.recordRequired(username, METHOD, uri(event, fields), status, "NF07_POLICY_" + event, success);
    }

    static String uri(String event, Map<String, String> fields) {
        StringBuilder uri = new StringBuilder(URI_PREFIX).append(event);
        char separator = '?';
        for (Map.Entry<String, String> field : fields.entrySet()) {
            uri.append(separator).append(field.getKey()).append('=')
                    .append(URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8));
            separator = '&';
        }
        if (uri.length() > MAX_URI) {
            throw new IllegalStateException("policy監査の本文が長すぎます");
        }
        return uri.toString();
    }
}
