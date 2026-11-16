package com.ses.privacy.production.retention.hold;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * reconciler が Journal から復元した hold の開始者を、開始要求の required 監査から引く（NF07-REL delta review P2-1）。
 *
 * <p>開始の service は予約の transaction の中で、Journal への append より前に {@code NF07_HOLD_START_REQUESTED} の監査
 * （本文 {@code /api/privacy/holds#START_REQUESTED?hold=<hold ID>&...}、username は開始者）を書く。Journal に開始 event が
 * あれば、この監査は同じ application DB に commit 済みである。ここからは次の全てを満たすときだけ {@code sys_user.id} を返し、
 * それ以外は空（開始者は不明のまま。fail-closed）:
 * <ul>
 *   <li>その hold ID の開始要求の監査が1件以上あり、username が1つに定まる（空・複数は不明）。</li>
 *   <li>その username の {@code sys_user} がちょうど1行で、id が正、tenant が hold の tenant と一致する（未設定は許す）。</li>
 *   <li>監査に記録された実行者の id（{@code human_user_id}）があれば、全てその id と一致する。</li>
 * </ul>
 * 値の信頼度は projection の他の値と同じ（どちらも application DB。Journal では確かめられない。plan §11 の残余）。
 * 照会できなければ例外を返し、呼出し側は不明として扱う。
 */
final class PrivacyHoldStarterLookup {

    private static final String START_REQUESTED_CODE = "NF07_HOLD_" + PrivacyHoldAudit.START_REQUESTED;

    private final JdbcTemplate jdbc;

    PrivacyHoldStarterLookup(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** hold の開始者の {@code sys_user.id}（一意に定まらなければ空）。 */
    OptionalLong starterOf(String tenantId, String holdId) {
        if (tenantId == null || holdId == null || !PrivacyHoldPartitions.HOLD_ID.matcher(holdId).matches()) {
            return OptionalLong.empty();
        }
        String prefix = PrivacyHoldAudit.URI_PREFIX + PrivacyHoldAudit.START_REQUESTED + "?hold=" + holdId + "&";
        // hold ID は英小文字・数字・ハイフンだけなので LIKE の特殊文字を含まない。前方一致は取得後に完全に確かめる
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT username, uri, human_user_id FROM t_audit_log "
                        + "WHERE method = ? AND application_code = ? AND uri LIKE ?",
                PrivacyHoldAudit.METHOD, START_REQUESTED_CODE, "%?hold=" + holdId + "&%");
        Set<String> usernames = new HashSet<>();
        Set<Long> recordedUserIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object uri = row.get("uri");
            if (!(uri instanceof String text) || !text.startsWith(prefix)) {
                continue;
            }
            Object username = row.get("username");
            usernames.add(username == null ? "" : username.toString());
            Object humanUserId = row.get("human_user_id");
            if (humanUserId instanceof Number number) {
                recordedUserIds.add(number.longValue());
            }
        }
        if (usernames.size() != 1) {
            return OptionalLong.empty();
        }
        String username = usernames.iterator().next();
        if (username.isBlank()) {
            return OptionalLong.empty();
        }
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, tenant_id FROM sys_user WHERE username = ?",
                username);
        if (users.size() != 1 || !(users.get(0).get("id") instanceof Number id) || id.longValue() <= 0) {
            return OptionalLong.empty();
        }
        Object userTenant = users.get(0).get("tenant_id");
        if (userTenant != null && !tenantId.equals(userTenant.toString())) {
            return OptionalLong.empty();
        }
        if (!recordedUserIds.isEmpty() && !(recordedUserIds.size() == 1 && recordedUserIds.contains(id.longValue()))) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(id.longValue());
    }
}
