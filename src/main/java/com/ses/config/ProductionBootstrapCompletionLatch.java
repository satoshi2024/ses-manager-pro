package com.ses.config;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * 本番初回bootstrapの完了marker（review P2-A）。
 *
 * <p>本番稼働後に期限付きbootstrap mode（{@link ProductionBootstrapMode}）を再び有効にすると、初期管理者のlocal loginと
 * 管理者作成・IdP登録の入口が開き、break-glass incidentの「起票1名＋互いに異なる承認者2名」を経ずに管理者権限を
 * 得られる（承認不要のbreak-glassに等しい）。そこで、bootstrapを無効にした起動で
 * {@link ProductionSecurityConfigurationValidator} の完全な起動検査が初めて通った時に、{@code m_system_config} の
 * {@value #CONFIG_KEY} へ完了日時（offset付きISO-8601）を書く。markerが有る状態でbootstrapを有効にするには
 * 承認済みの変更ticket（{@code app.security.bootstrap.reopen-ticket}）が必要で、再開は必須監査
 * {@code BOOTSTRAP_REOPENED} に残る。
 *
 * <ul>
 *   <li>判定はrowの有無で行う（値が空でも完了済みとして扱う。fail-closed）。</li>
 *   <li>書込は冪等で、rowが既に有れば上書きしない。並行起動で先に書かれた場合も上書きしない。</li>
 *   <li>先に必須監査 {@code BOOTSTRAP_COMPLETED} を残し、監査を永続化できなければmarkerも書かない。</li>
 *   <li>本keyは画面から閲覧・編集させない（{@code SystemConfigApiController} / {@code SystemConfigServiceImpl} の
 *       システム管理キー）。DB権限を持つ者による削除は本機構の対象外（DB権限はアプリの制御を迂回できる）。</li>
 * </ul>
 * 失敗時の扱い（起動を止める）は呼出側（{@link ProductionBootstrapMode}・{@link ProductionSecurityConfigurationValidator}）が決める。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionBootstrapCompletionLatch {

    /** 完了markerの {@code m_system_config.config_key}。 */
    public static final String CONFIG_KEY = "security.bootstrap.completed-at";

    static final String DESCRIPTION = "本番初回bootstrapの完了日時（システム管理。変更・削除しないこと）";

    /** 起動時の監査に使う {@code t_audit_log.method}（HTTP要求ではないため固定値）。 */
    static final String AUDIT_METHOD = "STARTUP";

    /** 起動時の監査に使う {@code t_audit_log.uri} の接頭辞（以降に {@code ;key=value} を連ねる）。 */
    static final String AUDIT_URI_PREFIX = "app.security.bootstrap";

    private static final int MAX_COMPLETED_AT_IN_AUDIT = 40;

    private final SystemConfigMapper systemConfigMapper;
    private final AuditLogService auditLogService;
    private final Clock clock;

    /**
     * 完了markerの値。markerが無ければ空。値が空のrowは空文字で返す（完了済みとして扱う）。
     * DBを読めなければ例外をそのまま投げる（呼出側でfail-closedにする）。
     */
    public Optional<String> findCompletedAt() {
        SystemConfig row = systemConfigMapper.selectById(CONFIG_KEY);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(row.getConfigValue() == null ? "" : row.getConfigValue().trim());
    }

    /**
     * 完了markerが無ければ書く（冪等）。新たに書いた場合だけtrue。
     * 先に必須監査 {@code BOOTSTRAP_COMPLETED} を残し、永続化できなければ例外にしてmarkerも書かない。
     */
    public boolean markCompletedIfAbsent() {
        if (systemConfigMapper.selectById(CONFIG_KEY) != null) {
            return false;
        }
        String completedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS));
        auditLogService.recordRequired(null, AUDIT_METHOD, auditUri("completed-at=" + completedAt), 200,
                "BOOTSTRAP_COMPLETED", true);
        try {
            if (systemConfigMapper.insert(new SystemConfig(CONFIG_KEY, completedAt, DESCRIPTION)) != 1) {
                throw new IllegalStateException("[bootstrap] 初回bootstrapの完了markerを書き込めません");
            }
        } catch (DuplicateKeyException e) {
            // 並行起動した別instanceが先に書いた（全停止配備では起きない想定）。上書きはしない。
            log.warn("[bootstrap] 初回bootstrapの完了markerは別の起動が先に書き込み済みです");
            return false;
        }
        log.warn("[bootstrap] 初回bootstrapの完了markerを書き込みました（{}={}）。以後のbootstrap再開には"
                + "承認済みの変更ticket（app.security.bootstrap.reopen-ticket）が必要です", CONFIG_KEY, completedAt);
        return true;
    }

    /**
     * 完了済みのbootstrapの再開を必須監査 {@code BOOTSTRAP_REOPENED} に残す（ticket・期限・完了日時）。
     * 永続化できなければ例外をそのまま投げる（呼出側で起動を止める）。
     */
    public void recordReopened(String reopenTicket, Instant expiresAt, String completedAt) {
        auditLogService.recordRequired(null, AUDIT_METHOD,
                auditUri("reopen-ticket=" + reopenTicket + ";expires-at=" + expiresAt
                        + ";completed-at=" + truncate(completedAt)),
                200, "BOOTSTRAP_REOPENED", true);
    }

    /** 完了markerを書けなかったことの監査（best-effort。記録の成否は起動拒否の判断に影響させない）。 */
    public void recordCompletionFailure() {
        try {
            auditLogService.record(null, AUDIT_METHOD, auditUri("completed-at=-"), 500,
                    "BOOTSTRAP_COMPLETION_MARK_FAILED", false);
        } catch (RuntimeException ignored) {
            // 起動は呼出側が拒否する（fail-closed）
        }
    }

    private static String auditUri(String detail) {
        return AUDIT_URI_PREFIX + ";" + detail;
    }

    private static String truncate(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.length() <= MAX_COMPLETED_AT_IN_AUDIT ? value : value.substring(0, MAX_COMPLETED_AT_IN_AUDIT);
    }
}
