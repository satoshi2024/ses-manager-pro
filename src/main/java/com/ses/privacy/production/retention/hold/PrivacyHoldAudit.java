package com.ses.privacy.production.retention.hold;

import com.ses.service.AuditLogService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * hold の required 監査（production-bridge-phased-plan.md §3.4.4 の5・§6.8）。
 *
 * <p>本文（URI）に入れてよいのは hold ID・対象種別・data element・ticket 参照・理由コード・D-05 の決定ID と、revision・選択・
 * hash の先頭などの技術値だけで、subject 識別子（subject 参照・partition・command ID）と理由本文・理由 hash は入れない
 * （{@code /api/audit-logs} は privacy-admin 以外の管理者も見られるため。理由 hash は短い本文なら総当たりで推測できる）。
 * 1行は {@value #MAX_URI} 文字以内（値の文字種を検査で限っているため、開始要求を {@code START_REQUESTED} と
 * {@code START_REASON} の2行に分ければ最長の値でも収まる）。記録できなければ例外を返し、呼出し側の transaction を失敗させる
 * （{@code recordRequired}）。状態を変えない reconciler の判定は監査に残さない（Review P3-3。WARN と metric だけ）。
 */
final class PrivacyHoldAudit {

    static final String METHOD = "PRIVACY_HOLD";
    static final String URI_PREFIX = "/api/privacy/holds#";
    private static final int MAX_URI = 500;

    static final String START_REQUESTED = "START_REQUESTED";
    static final String START_REASON = "START_REASON";
    static final String CHOICE = "CHOICE";
    static final String DATA_ELEMENT_ACKNOWLEDGED = "DATA_ELEMENT_ACKNOWLEDGED";
    static final String STARTED = "STARTED";
    static final String START_REJECTED = "START_REJECTED";
    static final String ACK_UNKNOWN = "ACK_UNKNOWN";
    static final String RECONCILE_PROJECTED = "RECONCILE_PROJECTED";
    static final String RECONCILE_CAUGHT_UP = "RECONCILE_CAUGHT_UP";
    static final String RECONCILE_ABANDONED = "RECONCILE_ABANDONED";

    private final AuditLogService auditLogService;

    PrivacyHoldAudit(AuditLogService auditLogService) {
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /** 監査本文の項目（挿入順）。 */
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
        auditLogService.recordRequired(username, METHOD, uri(event, fields), status, "NF07_HOLD_" + event, success);
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
            throw new IllegalStateException("hold監査の本文が長すぎます");
        }
        return uri.toString();
    }
}
