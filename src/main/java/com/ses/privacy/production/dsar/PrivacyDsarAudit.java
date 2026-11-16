package com.ses.privacy.production.dsar;

import com.ses.service.AuditLogService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * DSAR の required 監査（production-bridge-phased-plan.md §3.5.1 の P3-9・§6.8）。
 *
 * <p>本文（URI）に入れてよいのは受付番号と状態遷移（case の状態、link の状態）だけで、subject 参照・氏名・ticket・証跡の参照・
 * 範囲 snapshot の hash は入れない（{@code /api/audit-logs} は privacy-admin 以外の管理者も見られるため。hash は as-of と
 * request ID が分かれば subject ID の小さな空間を総当たりで推測できる）。記録は呼出し側の transaction の中で
 * {@code recordRequired} により行い、記録できなければ例外を返して操作ごと失敗させる。
 */
public class PrivacyDsarAudit {

    static final String METHOD = "PRIVACY_DSAR";
    static final String URI_PREFIX = "/api/privacy/dsar#";
    static final String APPLICATION_CODE_PREFIX = "NF07_DSAR_";
    private static final int MAX_URI = 500;

    static final String INTAKE_RECORDED = "INTAKE_RECORDED";
    static final String IDENTITY_VERIFIED = "IDENTITY_VERIFIED";
    static final String CANDIDATE_ADDED = "CANDIDATE_ADDED";
    static final String CANDIDATE_RESOLVED = "CANDIDATE_RESOLVED";
    static final String SCOPED = "SCOPED";
    static final String CLOSED = "CLOSED";

    private final AuditLogService auditLogService;

    public PrivacyDsarAudit(AuditLogService auditLogService) {
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
    }

    /** case の状態遷移を記録する（失敗は例外）。 */
    void transition(String username, String event, String requestNo, String from, String to) {
        record(username, event, fields("requestNo", requestNo, "from", from, "to", to));
    }

    /** link の状態遷移を記録する（subject 参照・link ID は入れない）。 */
    void linkTransition(String username, String event, String requestNo, String linkFrom, String linkTo) {
        record(username, event, fields("requestNo", requestNo, "linkFrom", linkFrom, "linkTo", linkTo));
    }

    private void record(String username, String event, Map<String, String> fields) {
        auditLogService.recordRequired(username, METHOD, uri(event, fields), 200, APPLICATION_CODE_PREFIX + event, true);
    }

    static Map<String, String> fields(String... keyValues) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                fields.put(keyValues[i], keyValues[i + 1]);
            }
        }
        return fields;
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
            throw new IllegalStateException("DSAR監査の本文が長すぎます");
        }
        return uri.toString();
    }
}
