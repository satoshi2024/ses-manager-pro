package com.ses.service.security.epoch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RB-ADMIN-GUARD review P3-1: 管理者の継続性guardのlock（全管理者と対象の利用者の行、NF01 lock rank 3 の (tenant_id, id) 昇順）が、
 * 加防護の利用者writerでそのtransactionの最初のrank行lock・書込みであることを、Docker無しでsourceから固定する。
 *
 * <p>guardのlockより前に対象の行（または作成した最大idの行）をlockすると、より小さいidの管理者の行をその後にlockすることになり、
 * 同rank昇順（計画 §3.5 規則3）が崩れて、別の利用者writerと循環待ちになり得る。実行時の順序は H2・mock では観測できない
 * （SysUserServiceImpl は MyBatis-Plus の基底実装が SqlSession を要する）ため、{@code ScopeSourceWriterFenceTest} と同じく
 * コメントを除いたsourceで、メソッド本体の中のguard呼出しより前に lock・書込みの呼出しが無いこと、guardの判定
 * （verify）が決定的な書込みの後・session失効や所属closeの前にあることを確かめる。</p>
 */
class AdministratorContinuityLockOrderTest {

    private static final String SYS_USER_SERVICE_IMPL = "com/ses/service/impl/SysUserServiceImpl.java";
    private static final String PERMISSION_GROUP_SERVICE_IMPL =
            "com/ses/service/security/impl/PermissionGroupManagementServiceImpl.java";

    /** guardのlockより前に置いてはいけない、rank行のlock・書込みの呼出し。 */
    private static final List<String> LOCK_OR_WRITE_CALLS = List.of(
            "ForUpdate", "ForShare", "lockActive(", "save(", "insert(", "update(", "updateById(", "removeById(",
            "deleteAllForUser(", "replaceAssignments(", "advance(", "advanceIfChanged(");

    @Test
    void 加防護の利用者writerはguardのlockを最初のrank行lockとして取る() {
        String users = WriterGuardSources.mainSource(SYS_USER_SERVICE_IMPL);
        String groups = WriterGuardSources.mainSource(PERMISSION_GROUP_SERVICE_IMPL);
        List<String> violations = new ArrayList<>();
        violations.addAll(guardFirst(SYS_USER_SERVICE_IMPL, users, "public void createUser(",
                "lockAdministratorContinuity(\"USER_CREATE\"", "save(sysUser)", "replaceAssignments(",
                "verifyAdministratorContinuity(continuity)"));
        violations.addAll(guardFirst(SYS_USER_SERVICE_IMPL, users, "public void updateUser(",
                "lockAdministratorContinuity(\"USER_UPDATE\", id)", "baseMapper.selectByIdForUpdate(id)",
                "updateById(sysUser)", "replaceAssignments(", "verifyAdministratorContinuity(continuity)"));
        violations.addAll(guardFirst(SYS_USER_SERVICE_IMPL, users, "public void updateUserStatus(",
                "lockAdministratorContinuity(\"USER_STATUS\", id)", "baseMapper.selectByIdForUpdate(id)",
                "update(new UpdateWrapper", "verifyAdministratorContinuity(continuity)",
                "closeOrganizationAssignments(id)", "revokeUserSessions(id"));
        violations.addAll(guardFirst(SYS_USER_SERVICE_IMPL, users, "public void deleteUser(",
                "lockAdministratorContinuity(\"USER_DELETE\", id)", "baseMapper.selectByIdForUpdate(id)",
                "removeById(id)", "verifyAdministratorContinuity(continuity)",
                "closeOrganizationAssignments(id)", "revokeUserSessions(id"));
        violations.addAll(guardFirst(PERMISSION_GROUP_SERVICE_IMPL, groups, "public void replaceAssignments(",
                "administratorContinuityGuard.lockAndCapture(\"PERMISSION_GROUP_REPLACE\"",
                "sysUserMapper.selectByIdForUpdate(userId)", "userPermissionGroupMapper.deleteAllForUser(",
                "userPermissionGroupMapper.insert(", "administratorContinuityGuard.verify(continuity)",
                "persistentSessionService.revokeAllForUser(", "insertAudit("));
        assertEquals(List.of(), violations,
                "guardのlockを加防護のwriterの最初のrank 3 lockにし、判定は決定的な書込みの後・失効の前に置く（RB-ADMIN-GUARD）");
    }

    /**
     * {@code marker} で始まるメソッド本体で、{@code guardCall} より前に lock・書込みの呼出しが無く、{@code guardCall} の後に
     * {@code laterTokens} がこの順に現れることを確かめる。
     */
    private static List<String> guardFirst(String relative, String source, String marker, String guardCall,
                                           String... laterTokens) {
        int at = source.indexOf(marker);
        if (at < 0) {
            return List.of(relative + ": " + marker + " が見つかりません");
        }
        int open = source.indexOf('{', at);
        String body = open < 0 ? "" : WriterGuardSources.callArguments(source, open + 1);
        int guard = body.indexOf(guardCall);
        if (guard < 0) {
            return List.of(relative + "#" + marker + ": " + guardCall + " が見つかりません");
        }
        List<String> problems = new ArrayList<>();
        String before = body.substring(0, guard);
        for (String call : LOCK_OR_WRITE_CALLS) {
            if (before.contains(call)) {
                problems.add(relative + "#" + marker + ": guardのlockより前に " + call + " があります");
            }
        }
        int cursor = guard + guardCall.length();
        for (String token : laterTokens) {
            int found = body.indexOf(token, cursor);
            if (found < 0) {
                problems.add(relative + "#" + marker + ": " + token + " が順序どおりに見つかりません（期待順 "
                        + guardCall + " → " + String.join(" → ", laterTokens) + "）");
                break;
            }
            cursor = found + token.length();
        }
        return problems;
    }
}
