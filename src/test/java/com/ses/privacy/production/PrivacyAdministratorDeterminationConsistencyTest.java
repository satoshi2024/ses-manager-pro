package com.ses.privacy.production;

import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.service.security.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * privacy 管理者の判定を、起動時の人数の SQL（{@code UserPermissionGroupMapper#selectEffectivePrivacyAdminUsernames}。
 * {@code PrivacyProductionConfigurationValidator} が数える）と実行時の判定（{@code AuthorizationServiceImpl#isAllowedForActor}）
 * の両側で同じ fixture に当て、答えが揃うことを固定する（A1H-1 delta review P3-N3）。実行時の側の unit test は group を mock で
 * 返すため {@code enabled = 1} の条件を評価しておらず、両側が別々に変わっても気付けなかった。
 *
 * <p>揃える点: 無効（{@code enabled = 0}）・論理削除の group、論理削除の所属行・拒否行は、実行時も評価しない（許可も拒否も）ため、
 * 起動時の人数でも除外の理由にしない。無効にした拒否 group では privacy から外せない（運用の注意。feature-switches.md 4.7.2）。
 * 意図的に違える点（安全側。P3-N4）: {@code privacy.<x>} の一部だけの拒否行は、実行時はその action だけを拒否するが、起動時は
 * 管理者として数えない（少なく数える＝起動を止める側）。大文字小文字の違う行（{@code PRIVACY.*}）は H2 では比べられないため
 * 実 MySQL の {@code PrivacyHoldServiceMySqlTest} が見る。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/permission-group-seed-h2.sql")
@Transactional
class PrivacyAdministratorDeterminationConsistencyTest {

    private static final String TENANT = "default";
    private static final String ADMIN = "管理者";
    /** permission-group-seed-h2.sql の privacy-admin（{@code privacy.*} の許可を持つ）。 */
    private static final long PRIVACY_ADMIN_GROUP = 6L;
    private static final long BASE = 9_712_000L;

    @Autowired
    private AuthorizationService authorizationService;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 起動時のprivacy管理者SQLと実行時の判定は無効groupと論理削除の拒否を同じく評価しない() {
        long provider = insertProvider();
        // 利用者ごとの、privacy-admin に加えた所属（無ければ privacy-admin だけ）と、両側の期待（privacy を使えるか）
        Map<String, Boolean> expected = new LinkedHashMap<>();
        long user = BASE;
        expected.put(admin(++user, "plain", provider), true);
        expected.put(admin(++user, "enabled-deny", provider, group(1, "privacy.*", true, false, false)), false);
        expected.put(admin(++user, "star-deny", provider, group(2, "*", true, false, false)), false);
        expected.put(admin(++user, "disabled-deny", provider, group(3, "privacy.*", false, false, false)), true);
        expected.put(admin(++user, "deleted-deny-row", provider, group(4, "privacy.*", true, true, false)), true);
        expected.put(admin(++user, "deleted-deny-group", provider, group(5, "privacy.*", true, false, true)), true);
        long deletedMembership = ++user;
        String deletedMembershipName = admin(deletedMembership, "deleted-membership", provider,
                group(6, "privacy.*", true, false, false));
        jdbc.update("UPDATE t_user_permission_group SET deleted_flag = 1 WHERE user_id = ? AND group_id = ?",
                deletedMembership, BASE + 106);
        expected.put(deletedMembershipName, true);

        List<String> counted = userPermissionGroupMapper.selectEffectivePrivacyAdminUsernames(TENANT, ADMIN, TENANT,
                provider);
        long id = BASE;
        for (Map.Entry<String, Boolean> entry : expected.entrySet()) {
            id++;
            boolean runtime = authorizationService.isAllowedForActor(TENANT, id, ADMIN, "privacy.hold.view");
            assertThat(counted.contains(entry.getKey())).as("起動時の人数に数えるか: %s", entry.getKey())
                    .isEqualTo(entry.getValue());
            assertThat(runtime).as("実行時に privacy を使えるか: %s", entry.getKey()).isEqualTo(entry.getValue());
        }
        assertThat(counted).as("fixture の provider の利用者だけを数える").hasSize(
                (int) expected.values().stream().filter(Boolean::booleanValue).count());
    }

    @Test
    void privacyの一部だけを拒否する行は起動時には数えず実行時はそのactionだけを拒否する() {
        long provider = insertProvider();
        long id = BASE + 50;
        String name = admin(id, "partial-deny", provider, group(10, "privacy.dsar.view", true, false, false));

        List<String> counted = userPermissionGroupMapper.selectEffectivePrivacyAdminUsernames(TENANT, ADMIN, TENANT,
                provider);

        // 少なく数える（起動を止める側）。実行時は拒否した action 以外を使える
        assertThat(counted).doesNotContain(name);
        assertThat(authorizationService.isAllowedForActor(TENANT, id, ADMIN, "privacy.hold.view")).isTrue();
        assertThat(authorizationService.isAllowedForActor(TENANT, id, ADMIN, "privacy.dsar.view")).isFalse();
    }

    /** 拒否行を1つ持つ group（{@code BASE + 100 + n}）。 */
    private long group(int n, String deniedAction, boolean enabled, boolean rowDeleted, boolean groupDeleted) {
        long id = BASE + 100 + n;
        jdbc.update("INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, TENANT, "nf07-consistency-" + n, "nf07-consistency-" + n,
                enabled ? 1 : 0, groupDeleted ? 1 : 0);
        jdbc.update("INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                + "VALUES (?, ?, ?, 1, ?)", TENANT, id, deniedAction, rowDeleted ? 1 : 0);
        return id;
    }

    /** privacy-admin と {@code groups} に所属し、fixture の provider に APPROVED の外部 identity を持つ有効な管理者。 */
    private String admin(long id, String name, long provider, long... groups) {
        String username = "nf07-consistency-" + name;
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, role, status, tenant_id, deleted_flag) "
                + "VALUES (?, ?, 'unused', ?, ?, 1, ?, 0)", id, username, name, ADMIN, TENANT);
        member(id, PRIVACY_ADMIN_GROUP);
        for (long group : groups) {
            member(id, group);
        }
        jdbc.update("INSERT INTO t_user_external_identity (tenant_id, user_id, provider_id, subject, review_status, "
                        + "reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, 'APPROVED', ?, 1, 0)",
                TENANT, id, provider, "nf07-consistency-subject-" + id, LocalDateTime.of(2026, 11, 1, 10, 0));
        return username;
    }

    private void member(long userId, long groupId) {
        jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, 1, 0)", TENANT, userId, groupId);
    }

    private long insertProvider() {
        long id = BASE + 900;
        jdbc.update("INSERT INTO m_identity_provider (id, tenant_id, provider_type, issuer_uri, client_id, enabled, "
                + "deleted_flag) VALUES (?, ?, 'OIDC', ?, 'nf07-consistency-client', 1, 0)", id, TENANT,
                "https://idp.example.invalid/nf07-consistency");
        return id;
    }
}
