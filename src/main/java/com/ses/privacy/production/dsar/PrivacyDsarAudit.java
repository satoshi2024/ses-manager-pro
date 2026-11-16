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

    /**
     * 監査の method 列の値。{@code t_audit_log.method} は {@code VARCHAR(10)}（V11）のため10文字以内にする（以前の
     * {@code PRIVACY_DSAR} は12文字で、MySQL の strict mode では監査の insert が失敗し、required 監査を書く DSAR の全操作が
     * 失敗していた。test は監査を fake にしていたため検出できなかった）。経路の識別は application code（{@code NF07_DSAR_<event>}）
     * で行う。列の長さは {@code AuditLogMethodLengthGuardTest} が main 全体で確かめる。
     */
    static final String METHOD = "PRIV_DSAR";
    static final String URI_PREFIX = "/api/privacy/dsar#";
    static final String APPLICATION_CODE_PREFIX = "NF07_DSAR_";
    private static final int MAX_URI = 500;

    static final String INTAKE_RECORDED = "INTAKE_RECORDED";
    static final String IDENTITY_VERIFIED = "IDENTITY_VERIFIED";
    static final String CANDIDATE_ADDED = "CANDIDATE_ADDED";
    static final String CANDIDATE_RESOLVED = "CANDIDATE_RESOLVED";
    static final String SCOPED = "SCOPED";
    static final String CLOSED = "CLOSED";
    /** NF07-A2b: 検索と草案 package の作成（SCOPED → PREVIEWED）。 */
    static final String PREVIEWED = "PREVIEWED";
    /** NF07-A2b: 作成者以外の承認（PREVIEWED → APPROVED）。 */
    static final String APPROVED = "APPROVED";
    /** NF07-A2b: 専用ダウンロード（状態は変えない）。 */
    static final String PACKAGE_DOWNLOADED = "PACKAGE_DOWNLOADED";
    /** NF07-A2b: 交付の記録（APPROVED → CONFIRMED。package は PURGED）。 */
    static final String DELIVERY_RECORDED = "DELIVERY_RECORDED";
    /** NF07-A2b: 期限超過・終了した case の package の削除（scheduler）。 */
    static final String PACKAGE_PURGED = "PACKAGE_PURGED";

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

    /**
     * case の状態を変えない出来事（専用ダウンロード・package の削除）を記録する（失敗は例外）。本文は受付番号だけで、package の
     * 保存名・内容 hash は入れない。
     */
    void event(String username, String event, String requestNo) {
        record(username, event, fields("requestNo", requestNo));
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
