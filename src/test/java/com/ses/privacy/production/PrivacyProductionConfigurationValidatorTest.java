package com.ses.privacy.production;

import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W3-14b: prod で NF07 の flag を ON にする前提の起動検査（production-bridge-phased-plan.md §3.1.6・§5.1）。
 *
 * <p>privacy-admin の所属判定は SQL で行うため、H2 上に専用 tenant の group・user・所属を作って実 mapper で確かめる
 * （rollback され、他の tenant の行は読まない）。実 MySQL の起動経路は Runner の MySQL 試験で補う。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyProductionConfigurationValidatorTest {

    private static final String TENANT = "nf07-14b-validator";
    /** JST 2026-11-14 12:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-14T03:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final long BASE_ID = 1_414_000L;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PermissionGroupMapper permissionGroupMapper;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;

    private long nextId;
    private long privacyAdminGroupId;
    private long roleAdminGroupId;

    @BeforeEach
    void setUpPermissionGroups() {
        nextId = BASE_ID;
        privacyAdminGroupId = insertGroup("privacy-admin", 1);
        allow(privacyAdminGroupId, "*");
        allow(privacyAdminGroupId, "privacy.*");
        roleAdminGroupId = insertGroup("role-admin", 1);
        allow(roleAdminGroupId, "*");
        deny(roleAdminGroupId, "privacy.*");
    }

    // ---------------------------------------------------------------- flag OFF

    @Test
    void flagが全てOFFなら承認設定もDBも見ずに起動を止めない() {
        PermissionGroupMapper groups = mock(PermissionGroupMapper.class);
        UserPermissionGroupMapper members = mock(UserPermissionGroupMapper.class);
        PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(
                flags(false, false), new PrivacyApprovalProperties(), oidc(), groups, members, CLOCK);

        assertDoesNotThrow(validator::validate);
        assertDoesNotThrow(validator::afterSingletonsInstantiated);
        verifyNoInteractions(groups, members);
    }

    // ---------------------------------------------------------------- flag ON

    @Test
    void 束縛とprivacy_admin2名が揃えば起動を受理する() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        member(admin("nf07b-ops-e"), privacyAdminGroupId);

        assertDoesNotThrow(validator(approval())::validate);
        assertDoesNotThrow(validator(approval())::afterSingletonsInstantiated);
    }

    @Test
    void 決定ID束縛が未設定なら起動を止め欠けた項目を固定コードで列挙する() {
        twoPrivacyAdmins();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validator(new PrivacyApprovalProperties())::validate);

        assertContainsAll(error, "privacy.retention.enabled", "decisionBinding.tenantId.missing",
                "decisionBinding.capabilities.missing", "decisionBinding.approvalDigest.missing");
    }

    @Test
    void 必要な束縛項目が1つでも欠ければそれぞれの欠落コードで起動を止める() {
        twoPrivacyAdmins();
        List<String> fields = List.of("tenantId", "capabilities", "approvalDigest",
                "d01.decisionId", "d01.signedOn", "d01.expiresOn", "d09.decisionId", "d09.signedOn", "d09.expiresOn");
        for (String field : fields) {
            PrivacyApprovalProperties approval = approval();
            clear(approval, field);

            IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval)::validate,
                    field);

            assertContainsAll(error, "decisionBinding." + field + ".missing");
        }
    }

    @Test
    void 決定の失効日以降は起動を止める() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties expired = approval();
        expired.getD09().setSignedOn("2025-11-15");
        expired.getD09().setExpiresOn("2026-11-14");
        expired.setApprovalDigest(PrivacyDecisionBinding.computeDigest(expired));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(expired)::validate);

        assertContainsAll(error, "decisionBinding.d09.expiresOn.expired");
    }

    @Test
    void 承認tenantが設定tenantと異なれば起動を止める() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties otherTenant = approval();
        otherTenant.setTenantId("default");
        otherTenant.setApprovalDigest(PrivacyDecisionBinding.computeDigest(otherTenant));

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(otherTenant)::validate);

        assertContainsAll(error, "decisionBinding.tenantId.mismatch");
    }

    @Test
    void digestを改竄した束縛は起動を止め設定値を出さない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties tampered = approval();
        tampered.getD01().setDecisionId("D01-NF07-2026-7777");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(tampered)::validate);

        assertContainsAll(error, "decisionBinding." + PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH);
        assertFalse(error.getMessage().contains("D01-NF07-2026-7777"), "決定IDを出さない");
        assertFalse(error.getMessage().contains(tampered.getApprovalDigest()), "digestを出さない");
        assertFalse(error.getMessage().contains("nf07b-"), "usernameを出さない");
    }

    @Test
    void privacy_adminが1名だけなら起動を止める() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);

        assertContainsAll(error, "privacyAdmins.insufficient[current=1;required=2]");
        assertFalse(error.getMessage().contains("nf07b-ops-d"), "usernameを出さない");
    }

    @Test
    void privacy_admin2名のうち1名が無効なら起動を止める() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long disabled = admin("nf07b-ops-e");
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = ?", disabled);
        member(disabled, privacyAdminGroupId);

        assertInsufficient(1);
    }

    // 同じtransaction内では MyBatis の local cache が同じ引数の照会結果を返すため、DBを書き換えて再検査する
    // 組合せは test method を分ける（本番の起動検査は1回だけ照会する）。

    @Test
    void privacy_admin2名のうち1名がロック中なら起動を止める() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long locked = admin("nf07b-ops-e");
        jdbcTemplate.update("UPDATE sys_user SET locked_until = ? WHERE id = ?",
                LocalDateTime.of(2026, 11, 14, 12, 30), locked);
        member(locked, privacyAdminGroupId);

        assertInsufficient(1);
    }

    @Test
    void ロック期限を過ぎたprivacy_adminは有効な管理者に数える() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long unlocked = admin("nf07b-ops-e");
        jdbcTemplate.update("UPDATE sys_user SET locked_until = ? WHERE id = ?",
                LocalDateTime.of(2026, 11, 14, 11, 30), unlocked);
        member(unlocked, privacyAdminGroupId);

        assertDoesNotThrow(validator(approval())::validate);
    }

    @Test
    void 論理削除_管理者以外のrole_他tenant_所属行の削除は有効なprivacy_adminに数えない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);

        long deletedUser = admin("nf07b-deleted");
        jdbcTemplate.update("UPDATE sys_user SET deleted_flag = 1 WHERE id = ?", deletedUser);
        member(deletedUser, privacyAdminGroupId);

        long sales = user("nf07b-sales", "営業", TENANT);
        member(sales, privacyAdminGroupId);

        long otherTenantUser = user("nf07b-other-tenant", "管理者", "default");
        member(otherTenantUser, privacyAdminGroupId);

        long removedMembership = admin("nf07b-removed");
        long membershipId = member(removedMembership, privacyAdminGroupId);
        jdbcTemplate.update("UPDATE t_user_permission_group SET deleted_flag = 1 WHERE id = ?", membershipId);

        assertInsufficient(1);
    }

    @Test
    void privacyを拒否するgroupにも所属する管理者とbreak_glass口座は数えない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long both = admin("nf07b-both");
        member(both, privacyAdminGroupId);
        member(both, roleAdminGroupId);
        member(admin("nf07b-bg-second"), privacyAdminGroupId);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                validator(approval(), Set.of("admin", "nf07b-bg-second"))::validate);

        assertContainsAll(error, "privacyAdmins.insufficient[current=1;required=2]");
    }

    @Test
    void privacy_admin_groupがprivacyの許可行を持たなければ起動を止める() {
        twoPrivacyAdmins();
        jdbcTemplate.update("UPDATE t_permission_group_action SET deleted_flag = 1 WHERE group_id = ? AND action_key = ?",
                privacyAdminGroupId, "privacy.*");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy");
    }

    @Test
    void privacy_admin_groupがprivacyの拒否行を持てば起動を止める() {
        twoPrivacyAdmins();
        deny(privacyAdminGroupId, "privacy.policy.view");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy");
    }

    @Test
    void privacy_admin_groupが無効なら起動を止める() {
        twoPrivacyAdmins();
        jdbcTemplate.update("UPDATE m_permission_group SET enabled = 0 WHERE id = ?", privacyAdminGroupId);

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy", "privacyAdmins.insufficient[current=0;required=2]");
    }

    @Test
    void privacy_admin以外のgroupがprivacy系の許可行を持てば起動を止めgroup_keyだけを出す() {
        twoPrivacyAdmins();
        long copied = insertGroup("custom-privacy-copy", 1);
        allow(copied, "privacy.*");
        long narrow = insertGroup("custom-policy-viewer", 1);
        allow(narrow, "privacy.policy.view");

        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);

        assertContainsAll(error, "privacyAllow.outsidePrivacyAdmin[custom-policy-viewer;custom-privacy-copy]");
    }

    @Test
    void 拒否行_削除済みの許可行_無効なgroupのprivacy許可は許可行として数えない() {
        twoPrivacyAdmins();
        long denying = insertGroup("custom-denying", 1);
        deny(denying, "privacy.policy.view");
        long deletedRow = insertGroup("custom-deleted-row", 1);
        long rowId = allow(deletedRow, "privacy.*");
        jdbcTemplate.update("UPDATE t_permission_group_action SET deleted_flag = 1 WHERE id = ?", rowId);
        long disabledGroup = insertGroup("custom-disabled", 0);
        allow(disabledGroup, "privacy.*");
        long similar = insertGroup("custom-privacy-like", 1);
        allow(similar, "privacy-retention.view");

        assertDoesNotThrow(validator(approval())::validate);
    }

    @Test
    void dsarをONにすると前提が未実装のため束縛が揃っても起動を止める() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties both = approval();
        both.setCapabilities(new ArrayList<>(List.of("retention", "dsar")));
        both.getD06().setDecisionId("D06-NF07-2026-0006");
        both.getD06().setSignedOn("2026-11-05");
        both.getD06().setExpiresOn("2027-11-04");
        both.setApprovalDigest(PrivacyDecisionBinding.computeDigest(both));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                new PrivacyProductionConfigurationValidator(flags(true, true), both, oidc(Set.of()),
                        permissionGroupMapper, userPermissionGroupMapper, CLOCK)::validate);

        assertContainsAll(error, "privacy.dsar.enabled", "dsar.prerequisitesNotImplemented");
        assertFalse(error.getMessage().contains("decisionBinding."), "束縛自体は揃っている");
    }

    @Test
    void 照会できなければ確認できないとして起動を止め例外の本文を出さない() {
        PermissionGroupMapper groups = mock(PermissionGroupMapper.class);
        UserPermissionGroupMapper members = mock(UserPermissionGroupMapper.class);
        when(groups.countPrivacyAdminGroupsGrantingPrivacy(any())).thenThrow(new IllegalStateException("secret-sql-1"));
        when(groups.selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin(any()))
                .thenThrow(new IllegalStateException("secret-sql-2"));
        when(members.selectEffectivePrivacyAdminUsernames(any(), any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-3"));
        PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(
                flags(true, false), approval(), oidc(Set.of()), groups, members, CLOCK);

        IllegalStateException error = assertThrows(IllegalStateException.class, validator::validate);

        assertContainsAll(error, "privacyAdminGroup.unverifiable", "privacyAdmins.unverifiable",
                "privacyAllow.unverifiable");
        assertFalse(error.getMessage().contains("secret-sql"), "例外の本文を出さない");
    }

    @Test
    void 本番profileだけで登録される() {
        Profile profile = PrivacyProductionConfigurationValidator.class.getAnnotation(Profile.class);
        assertArrayEquals(new String[]{"prod"}, profile.value());
    }

    // ---------------------------------------------------------------- helpers

    private void twoPrivacyAdmins() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        member(admin("nf07b-ops-e"), privacyAdminGroupId);
    }

    private void assertInsufficient(int current) {
        IllegalStateException error = assertThrows(IllegalStateException.class, validator(approval())::validate);
        assertContainsAll(error, "privacyAdmins.insufficient[current=" + current + ";required=2]");
    }

    private static void assertContainsAll(IllegalStateException error, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(error.getMessage().contains(fragment), () -> fragment + " が無い: " + error.getMessage());
        }
    }

    private PrivacyProductionConfigurationValidator validator(PrivacyApprovalProperties approval) {
        return validator(approval, Set.of("admin", "bg-second"));
    }

    private PrivacyProductionConfigurationValidator validator(PrivacyApprovalProperties approval,
                                                              Set<String> breakGlassUsernames) {
        return new PrivacyProductionConfigurationValidator(flags(true, false), approval, oidc(breakGlassUsernames),
                permissionGroupMapper, userPermissionGroupMapper, CLOCK);
    }

    private static PrivacyFeatureProperties flags(boolean retention, boolean dsar) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        properties.getDsar().setEnabled(dsar);
        return properties;
    }

    private static OidcSecurityProperties oidc() {
        return oidc(Set.of());
    }

    private static OidcSecurityProperties oidc(Set<String> breakGlassUsernames) {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setTenantId(TENANT);
        properties.setBreakGlassUsernames(new LinkedHashSet<>(breakGlassUsernames));
        return properties;
    }

    /** 専用 tenant 向けの完全な束縛（retention、D-01・D-09）。 */
    private static PrivacyApprovalProperties approval() {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.setTenantId(TENANT);
        approval.setCapabilities(new ArrayList<>(List.of("retention")));
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD09().setDecisionId("D09-NF07-2026-0009");
        approval.getD09().setSignedOn("2026-11-10");
        approval.getD09().setExpiresOn("2027-05-31");
        approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approval));
        return approval;
    }

    private static void clear(PrivacyApprovalProperties approval, String field) {
        switch (field) {
            case "tenantId" -> approval.setTenantId(" ");
            case "capabilities" -> approval.setCapabilities(new ArrayList<>());
            case "approvalDigest" -> approval.setApprovalDigest(" ");
            case "d01.decisionId" -> approval.getD01().setDecisionId(" ");
            case "d01.signedOn" -> approval.getD01().setSignedOn(" ");
            case "d01.expiresOn" -> approval.getD01().setExpiresOn(" ");
            case "d09.decisionId" -> approval.getD09().setDecisionId(" ");
            case "d09.signedOn" -> approval.getD09().setSignedOn(" ");
            case "d09.expiresOn" -> approval.getD09().setExpiresOn(" ");
            default -> throw new IllegalArgumentException(field);
        }
    }

    private long insertGroup(String groupKey, int enabled) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO m_permission_group (id, tenant_id, group_key, group_name, enabled, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, 0)", id, TENANT, groupKey, groupKey, enabled);
        return id;
    }

    private long allow(long groupId, String actionKey) {
        return action(groupId, actionKey, 0);
    }

    private long deny(long groupId, String actionKey) {
        return action(groupId, actionKey, 1);
    }

    private long action(long groupId, String actionKey, int denyFlag) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO t_permission_group_action (id, tenant_id, group_id, action_key, deny_flag, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, 0)", id, TENANT, groupId, actionKey, denyFlag);
        return id;
    }

    private long admin(String username) {
        return user(username, "管理者", TENANT);
    }

    private long user(String username, String role, String tenantId) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, real_name, role, status, tenant_id, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, 1, ?, 0)", id, username, "unused", username, role, tenantId);
        return id;
    }

    private long member(long userId, long groupId) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO t_user_permission_group (id, tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, 0)", id, TENANT, userId, groupId, 1L);
        return id;
    }
}
