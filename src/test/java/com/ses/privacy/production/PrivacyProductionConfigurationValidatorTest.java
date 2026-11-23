package com.ses.privacy.production;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.IdentityProvider;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String ISSUER = "https://idp.invalid/nf07-14b";

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PermissionGroupMapper permissionGroupMapper;
    @Autowired
    private UserPermissionGroupMapper userPermissionGroupMapper;
    @Autowired
    private IdentityProviderMapper identityProviderMapper;

    private long nextId;
    private long providerId;
    private long privacyAdminGroupId;
    private long roleAdminGroupId;

    @BeforeEach
    void setUpPermissionGroups() {
        nextId = BASE_ID;
        providerId = insertProvider(TENANT, "OIDC", ISSUER, 1);
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
        IdentityProviderMapper providers = mock(IdentityProviderMapper.class);
        PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(
                flags(false, false), new PrivacyApprovalProperties(), oidc(), groups, members, providers, CLOCK);

        assertDoesNotThrow(validator::validate);
        assertDoesNotThrow(validator::afterSingletonsInstantiated);
        verifyNoInteractions(groups, members, providers);
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
    void 決定ID束縛が未設定なら開放の条件を満たさず欠けた項目を固定コードで列挙する() {
        twoPrivacyAdmins();

        IllegalStateException error = blocked(validator(new PrivacyApprovalProperties()));

        assertContainsAll(error, "privacy.retention.enabled", "decisionBinding.tenantId.missing",
                "decisionBinding.capabilities.missing", "decisionBinding.approvalDigest.missing");
    }

    @Test
    void 必要な束縛項目が1つでも欠ければそれぞれの欠落コードで開放の条件を満たさない() {
        twoPrivacyAdmins();
        List<String> fields = List.of("tenantId", "capabilities", "approvalDigest",
                "d01.decisionId", "d01.signedOn", "d01.expiresOn", "d09.decisionId", "d09.signedOn", "d09.expiresOn");
        for (String field : fields) {
            PrivacyApprovalProperties approval = approval();
            clear(approval, field);

            IllegalStateException error = blocked(validator(approval));

            assertContainsAll(error, "decisionBinding." + field + ".missing");
        }
    }

    // ---------------------------------------------------------------- 時間要因（NF06・NF08 と同じく起動は止めない）

    @Test
    void 決定の失効日以降は起動を止めずNF07を閉じたままWARNを出す() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties expired = approval();
        expired.getD09().setSignedOn("2025-11-15");
        expired.getD09().setExpiresOn("2026-11-14");
        expired.setApprovalDigest(PrivacyDecisionBinding.computeDigest(expired));

        List<ILoggingEvent> warnings = captureWarnings(() -> assertDoesNotThrow(validator(expired)::validate));

        assertTrue(warnings.stream().anyMatch(event -> event.getFormattedMessage().contains(
                        PrivacyProductionConfigurationValidator.LOG_CODE_STOPPED)
                        && event.getFormattedMessage().contains("decisionBinding.d09.expiresOn.expired")),
                "起動時にWARNを出す");
        assertTrue(warnings.stream().noneMatch(event -> event.getFormattedMessage().contains("D09-NF07")),
                "決定IDをWARNへ出さない");
        assertFalse(monitor(expired).satisfied(), "入口はOFFと同じ扱い");
    }

    @Test
    void 署名日前の決定は起動を止めずNF07を閉じたままWARNを出す() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties notYet = approval();
        notYet.getD09().setSignedOn("2026-11-15");
        notYet.setApprovalDigest(PrivacyDecisionBinding.computeDigest(notYet));

        List<ILoggingEvent> warnings = captureWarnings(() -> assertDoesNotThrow(validator(notYet)::validate));

        assertTrue(warnings.stream().anyMatch(event -> event.getFormattedMessage().contains(
                        PrivacyProductionConfigurationValidator.LOG_CODE_STOPPED)
                        && event.getFormattedMessage().contains("decisionBinding.d09.signedOn.future")));
        assertFalse(monitor(notYet).satisfied(), "入口はOFFと同じ扱い");
    }

    @Test
    void 失効と構造的な不足が同時なら開放の条件を満たさない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties expiredAndTampered = approval();
        expiredAndTampered.getD09().setSignedOn("2025-11-15");
        expiredAndTampered.getD09().setExpiresOn("2026-11-14");
        expiredAndTampered.setApprovalDigest(PrivacyDecisionBinding.computeDigest(expiredAndTampered));
        expiredAndTampered.getD01().setDecisionId("D01-NF07-2026-7777");

        IllegalStateException tampered = blocked(validator(expiredAndTampered));
        assertContainsAll(tampered, "decisionBinding." + PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH,
                "decisionBinding.d09.expiresOn.expired");

        PrivacyApprovalProperties expired = approval();
        expired.getD09().setSignedOn("2025-11-15");
        expired.getD09().setExpiresOn("2026-11-14");
        expired.setApprovalDigest(PrivacyDecisionBinding.computeDigest(expired));
        // privacy-admin の条件は時間要因と同時でも起動を止める（mapperは0件・空・providerなしを返す）
        IllegalStateException noAdmins = blocked(new PrivacyProductionConfigurationValidator(flags(true, false), expired, oidc(Set.of()),
                        mock(PermissionGroupMapper.class), mock(UserPermissionGroupMapper.class),
                        mock(IdentityProviderMapper.class), CLOCK));
        assertContainsAll(noAdmins, "privacyAdminGroup.notGrantingPrivacy", "privacyAdmins.oidcProviderUnavailable",
                "decisionBinding.d09.expiresOn.expired");
    }

    @Test
    void ONのcapabilityが承認capabilityに無ければ開放の条件を満たさない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties dsarOnly = approval();
        dsarOnly.setCapabilities(new ArrayList<>(List.of("dsar")));
        dsarOnly.getD06().setDecisionId("D06-NF07-2026-0006");
        dsarOnly.getD06().setSignedOn("2026-11-05");
        dsarOnly.getD06().setExpiresOn("2027-11-04");
        dsarOnly.setApprovalDigest(PrivacyDecisionBinding.computeDigest(dsarOnly));

        IllegalStateException error = blocked(validator(dsarOnly));

        assertContainsAll(error, "decisionBinding.capabilities.retention.notApproved");
    }

    @Test
    void 承認tenantが設定tenantと異なれば開放の条件を満たさない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties otherTenant = approval();
        otherTenant.setTenantId("default");
        otherTenant.setApprovalDigest(PrivacyDecisionBinding.computeDigest(otherTenant));

        IllegalStateException error = blocked(validator(otherTenant));

        assertContainsAll(error, "decisionBinding.tenantId.mismatch");
    }

    @Test
    void digestを改竄した束縛は開放の条件を満たさず設定値を出さない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties tampered = approval();
        tampered.getD01().setDecisionId("D01-NF07-2026-7777");

        IllegalStateException error = blocked(validator(tampered));

        assertContainsAll(error, "decisionBinding." + PrivacyDecisionBinding.VIOLATION_DIGEST_MISMATCH);
        assertFalse(error.getMessage().contains("D01-NF07-2026-7777"), "決定IDを出さない");
        assertFalse(error.getMessage().contains(tampered.getApprovalDigest()), "digestを出さない");
        assertFalse(error.getMessage().contains("nf07b-"), "usernameを出さない");
    }

    @Test
    void privacy_adminが1名だけなら開放の条件を満たさない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);

        IllegalStateException error = blocked(validator(approval()));

        assertContainsAll(error, "privacyAdmins.insufficient[current=1;required=2]");
        assertFalse(error.getMessage().contains("nf07b-ops-d"), "usernameを出さない");
    }

    @Test
    void privacy_admin2名のうち1名が無効なら開放の条件を満たさない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long disabled = admin("nf07b-ops-e");
        jdbcTemplate.update("UPDATE sys_user SET status = 0 WHERE id = ?", disabled);
        member(disabled, privacyAdminGroupId);

        assertInsufficient(1);
    }

    // 同じtransaction内では MyBatis の local cache が同じ引数の照会結果を返すため、DBを書き換えて再検査する
    // 組合せは test method を分ける（本番の起動検査は1回だけ照会する）。

    @Test
    void ロック中のprivacy_adminも起動時の人数に数える() {
        // 未認証者がlogin失敗でロックを起こせるため、ロックで起動を止めない（review P2-1）。実行時は要求ごとに拒否される。
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long locked = admin("nf07b-ops-e");
        jdbcTemplate.update("UPDATE sys_user SET locked_until = ?, failed_count = 5 WHERE id = ?",
                LocalDateTime.of(2026, 11, 14, 12, 30), locked);
        member(locked, privacyAdminGroupId);

        assertDoesNotThrow(validator(approval())::validate);
    }

    @Test
    void OIDCでログインできない管理者は有効なprivacy_adminに数えない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);

        long noLink = user("nf07b-no-link", "管理者", TENANT);
        member(noLink, privacyAdminGroupId);

        long quarantined = user("nf07b-quarantined", "管理者", TENANT);
        identity(quarantined, providerId, "QUARANTINED");
        member(quarantined, privacyAdminGroupId);

        long deletedLink = user("nf07b-deleted-link", "管理者", TENANT);
        long linkId = identity(deletedLink, providerId, "APPROVED");
        jdbcTemplate.update("UPDATE t_user_external_identity SET deleted_flag = 1 WHERE id = ?", linkId);
        member(deletedLink, privacyAdminGroupId);

        long otherProvider = user("nf07b-other-provider", "管理者", TENANT);
        identity(otherProvider, insertProvider(TENANT, "OIDC", ISSUER + "/other", 1), "APPROVED");
        member(otherProvider, privacyAdminGroupId);

        assertInsufficient(1);
    }

    @Test
    void 設定tenant_issuerの有効なOIDC_providerが無ければ開放の条件を満たさない() {
        twoPrivacyAdmins();
        jdbcTemplate.update("UPDATE m_identity_provider SET enabled = 0 WHERE id = ?", providerId);

        IllegalStateException disabled = blocked(validator(approval()));

        assertContainsAll(disabled, "privacyAdmins.oidcProviderUnavailable");
        assertFalse(disabled.getMessage().contains("privacyAdmins.insufficient"), "人数は数えない");
    }

    @Test
    void issuerが未設定ならOIDC_providerを確認できないとして開放の条件を満たさない() {
        twoPrivacyAdmins();
        OidcSecurityProperties withoutIssuer = oidc(Set.of());
        withoutIssuer.setIssuerUri(" ");

        IllegalStateException error = blocked(new PrivacyProductionConfigurationValidator(flags(true, false), approval(), withoutIssuer,
                        permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, CLOCK));

        assertContainsAll(error, "privacyAdmins.oidcProviderUnavailable");
    }

    @Test
    void privacy系の個別の拒否行を持つgroupにも所属する管理者は数えない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        long narrowDeny = insertGroup("custom-policy-deny", 1);
        deny(narrowDeny, "privacy.policy.view");
        long partlyDenied = admin("nf07b-partly-denied");
        member(partlyDenied, privacyAdminGroupId);
        member(partlyDenied, narrowDeny);

        assertInsufficient(1);
    }

    @Test
    void break_glass_usernameは大文字小文字を区別せず除く() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        member(admin("nf07b-bg-second"), privacyAdminGroupId);

        IllegalStateException error = blocked(validator(approval(), Set.of(" NF07B-BG-Second ")));

        assertContainsAll(error, "privacyAdmins.insufficient[current=1;required=2]");
    }

    @Test
    void 論理削除_管理者以外のrole_他tenant_所属行の削除は有効なprivacy_adminに数えない() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);

        long deletedUser = admin("nf07b-deleted");
        jdbcTemplate.update("UPDATE sys_user SET deleted_flag = 1 WHERE id = ?", deletedUser);
        member(deletedUser, privacyAdminGroupId);

        long sales = user("nf07b-sales", "営業", TENANT);
        identity(sales, providerId, "APPROVED");
        member(sales, privacyAdminGroupId);

        long otherTenantUser = user("nf07b-other-tenant", "管理者", "default");
        identity(otherTenantUser, providerId, "APPROVED");
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

        IllegalStateException error = blocked(validator(approval(), Set.of("admin", "nf07b-bg-second")));

        assertContainsAll(error, "privacyAdmins.insufficient[current=1;required=2]");
    }

    @Test
    void privacy_admin_groupがprivacyの許可行を持たなければ開放の条件を満たさない() {
        twoPrivacyAdmins();
        jdbcTemplate.update("UPDATE t_permission_group_action SET deleted_flag = 1 WHERE group_id = ? AND action_key = ?",
                privacyAdminGroupId, "privacy.*");

        IllegalStateException error = blocked(validator(approval()));

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy");
    }

    @Test
    void privacy_admin_groupがprivacyの拒否行を持てば開放の条件を満たさない() {
        twoPrivacyAdmins();
        deny(privacyAdminGroupId, "privacy.policy.view");

        IllegalStateException error = blocked(validator(approval()));

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy");
    }

    @Test
    void privacy_admin_groupが無効なら開放の条件を満たさない() {
        twoPrivacyAdmins();
        jdbcTemplate.update("UPDATE m_permission_group SET enabled = 0 WHERE id = ?", privacyAdminGroupId);

        IllegalStateException error = blocked(validator(approval()));

        assertContainsAll(error, "privacyAdminGroup.notGrantingPrivacy", "privacyAdmins.insufficient[current=0;required=2]");
    }

    @Test
    void privacy_admin以外のgroupがprivacy系の許可行を持てば開放の条件を満たさずgroup_keyだけを出す() {
        twoPrivacyAdmins();
        long copied = insertGroup("custom-privacy-copy", 1);
        allow(copied, "privacy.*");
        long narrow = insertGroup("custom-policy-viewer", 1);
        allow(narrow, "privacy.policy.view");

        IllegalStateException error = blocked(validator(approval()));

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

    /**
     * NF07-A2b: dsar の起動時検査は実検査（§5.1）。D-01・D-06・D-09 の束縛、期限規則、package の最長保存期間、F2 provider 1件以上が
     * 揃えば ON で起動し、どれかが欠ければ固定コードで起動を止める（以前の {@code dsar.prerequisitesNotImplemented} は返さない）。
     */
    @Test
    void dsarは束縛と期限規則とpackage保存期間とF2_providerが揃えばONで起動する() {
        twoPrivacyAdmins();
        PrivacyFeatureProperties flags = dsarFlags(30, Duration.ofDays(30));

        assertDoesNotThrow(dsarValidator(flags, dsarApproval(), registryWithProvider())::validate);
    }

    @Test
    void dsarは期限規則とpackage保存期間とF2_providerのどれかが欠ければそれぞれの固定コードで開放の条件を満たさない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties approval = dsarApproval();

        IllegalStateException missingAll = blocked(dsarValidator(dsarFlags(null, null), approval, new ProductionPrivacyProviderRegistry(List.of())));
        assertContainsAll(missingAll, "privacy.dsar.enabled", "dsar.dueDaysUnset", "dsar.packageMaxAgeUnset",
                "dsar.providersUnregistered");
        assertFalse(missingAll.getMessage().contains("prerequisitesNotImplemented"));
        assertFalse(missingAll.getMessage().contains("decisionBinding."), "束縛自体は揃っている");

        IllegalStateException outOfRange = blocked(dsarValidator(dsarFlags(367, Duration.ofMinutes(30)), approval, registryWithProvider()));
        assertContainsAll(outOfRange, "dsar.dueDaysUnset", "dsar.packageMaxAgeUnset");
        assertFalse(outOfRange.getMessage().contains("dsar.providersUnregistered"));

        IllegalStateException withoutRegistry = blocked(new PrivacyProductionConfigurationValidator(dsarFlags(30, Duration.ofDays(30)), approval, oidc(Set.of()),
                        permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, CLOCK));
        assertContainsAll(withoutRegistry, "dsar.providersUnregistered");
    }

    @Test
    void dsarはD06の決定ID束縛が欠ければ他の前提が揃っても開放の条件を満たさない() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties withoutD06 = dsarApproval();
        withoutD06.getD06().setDecisionId(" ");
        withoutD06.setApprovalDigest(PrivacyDecisionBinding.computeDigest(withoutD06));

        IllegalStateException error = blocked(dsarValidator(dsarFlags(30, Duration.ofDays(30)), withoutD06, registryWithProvider()));

        assertContainsAll(error, "privacy.dsar.enabled", "decisionBinding.");
        assertTrue(PrivacyDecisionBinding.requiredDecisions(PrivacyAccessGuard.Capability.DSAR)
                .contains(PrivacyDecisionBinding.Decision.D06), "DSAR は D-06 の束縛を要する");
    }

    @Test
    void 照会できなければ確認できないとして開放の条件を満たさず例外の本文を出さない() {
        PermissionGroupMapper groups = mock(PermissionGroupMapper.class);
        UserPermissionGroupMapper members = mock(UserPermissionGroupMapper.class);
        IdentityProviderMapper providers = mock(IdentityProviderMapper.class);
        when(groups.countPrivacyAdminGroupsGrantingPrivacy(any())).thenThrow(new IllegalStateException("secret-sql-1"));
        when(groups.selectGroupKeysAllowingPrivacyOutsidePrivacyAdmin(any()))
                .thenThrow(new IllegalStateException("secret-sql-2"));
        IdentityProvider provider = new IdentityProvider();
        provider.setId(1L);
        provider.setProviderType("OIDC");
        when(providers.selectEnabledByTenantAndIssuer(TENANT, ISSUER)).thenReturn(provider);
        when(members.selectEffectivePrivacyAdminUsernames(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-3"));
        PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(
                flags(true, false), approval(), oidc(Set.of()), groups, members, providers, CLOCK);

        IllegalStateException error = blocked(validator);

        assertContainsAll(error, "privacyAdminGroup.unverifiable", "privacyAdmins.unverifiable",
                "privacyAllow.unverifiable");
        assertFalse(error.getMessage().contains("secret-sql"), "例外の本文を出さない");

        IdentityProviderMapper brokenProviders = mock(IdentityProviderMapper.class);
        when(brokenProviders.selectEnabledByTenantAndIssuer(any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-4"));
        IllegalStateException providerError = blocked(new PrivacyProductionConfigurationValidator(flags(true, false), approval(), oidc(Set.of()),
                        mock(PermissionGroupMapper.class), mock(UserPermissionGroupMapper.class),
                        brokenProviders, CLOCK));
        assertContainsAll(providerError, "privacyAdmins.unverifiable");
        assertFalse(providerError.getMessage().contains("secret-sql"), "例外の本文を出さない");
    }

    @Test
    void 本番profileだけで登録される() {
        Profile profile = PrivacyProductionConfigurationValidator.class.getAnnotation(Profile.class);
        assertArrayEquals(new String[]{"prod"}, profile.value());
    }

    // ---------------------------------------------------------------- helpers

    private static List<ILoggingEvent> captureWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PrivacyProductionConfigurationValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    /** 起動後の入口の判定（PrivacyDecisionWindowMonitor）を同じ設定・同じ時刻で作る。 */
    private static PrivacyDecisionWindowMonitor monitor(PrivacyApprovalProperties approval) {
        return new PrivacyDecisionWindowMonitor(flags(true, false), approval, oidc(), CLOCK, (MeterRegistry) null);
    }

    // ---------------------------------------------------------------- 管理者の継続性guard（RB-ADMIN-GUARD）

    @Test
    void 継続性guard向けの判定はflagがOFFでも起動時と同じ照会でprivacy_adminを数える() {
        long first = admin("nf07b-ops-d");
        member(first, privacyAdminGroupId);
        member(admin("nf07b-ops-e"), privacyAdminGroupId);
        PrivacyProductionConfigurationValidator flagsOff = new PrivacyProductionConfigurationValidator(
                flags(false, false), approval(), oidc(Set.of("admin", "bg-second")), permissionGroupMapper,
                userPermissionGroupMapper, identityProviderMapper, CLOCK);

        org.junit.jupiter.api.Assertions.assertEquals(List.of(com.ses.config.AdministratorConditionCheck.evaluated(
                        com.ses.config.AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS, 2, 2, true)),
                flagsOff.checkAdministratorConditions());
        assertDoesNotThrow(validator(approval())::validate);

        jdbcTemplate.update("UPDATE t_user_permission_group SET deleted_flag = 1 WHERE user_id = ? AND group_id = ?",
                first, privacyAdminGroupId);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(com.ses.config.AdministratorConditionCheck.evaluated(
                        com.ses.config.AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS, 1, 2, false)),
                flagsOff.checkAdministratorConditions());
        assertInsufficient(1);
    }

    @Test
    void 継続性guard向けの判定は照会の失敗を確認できないとして返しvalidateの扱いは変えない() {
        UserPermissionGroupMapper brokenMembers = mock(UserPermissionGroupMapper.class);
        when(brokenMembers.selectEffectivePrivacyAdminUsernames(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-5"));
        IdentityProviderMapper brokenProviders = mock(IdentityProviderMapper.class);
        when(brokenProviders.selectEnabledByTenantAndIssuer(any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-6"));
        com.ses.config.AdministratorConditionCheck unverifiable = com.ses.config.AdministratorConditionCheck.unverifiable(
                com.ses.config.AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS, 2);

        for (PrivacyProductionConfigurationValidator broken : List.of(
                new PrivacyProductionConfigurationValidator(flags(true, false), approval(), oidc(Set.of()),
                        permissionGroupMapper, brokenMembers, identityProviderMapper, CLOCK),
                new PrivacyProductionConfigurationValidator(flags(true, false), approval(), oidc(Set.of()),
                        permissionGroupMapper, userPermissionGroupMapper, brokenProviders, CLOCK))) {
            org.junit.jupiter.api.Assertions.assertEquals(List.of(unverifiable), broken.checkAdministratorConditions());
            // 起動時の検査でも privacyAdmins.unverifiable（評価できない類。起動は止めない。FE-C05）。
            assertContainsAll(blocked(broken), "privacyAdmins.unverifiable");
        }

        // issuer未設定でproviderを解決できないだけ（照会の失敗ではない）なら、validate と同じく満たさない扱いにする。
        OidcSecurityProperties issuerUnset = oidc(Set.of());
        issuerUnset.setIssuerUri(" ");
        PrivacyProductionConfigurationValidator withoutIssuer = new PrivacyProductionConfigurationValidator(
                flags(false, false), approval(), issuerUnset, permissionGroupMapper, userPermissionGroupMapper,
                identityProviderMapper, CLOCK);
        org.junit.jupiter.api.Assertions.assertEquals(List.of(com.ses.config.AdministratorConditionCheck.evaluated(
                        com.ses.config.AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS, 0, 2, false)),
                withoutIssuer.checkAdministratorConditions());
    }

    // ---------------------------------------------------------------- 評価器（FE-C05）

    /** 評価器は validate と同じ method を呼び、同じ違反 code を返す。照会の失敗は評価できない類（registry が直す）。 */
    @Test
    void 評価器の違反codeは起動時検査と同じで照会の失敗は評価できない類になる() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        PrivacyApprovalProperties tampered = approval();
        tampered.getD01().setDecisionId("D01-NF07-2026-7777");
        PrivacyProductionConfigurationValidator validator = validator(tampered);
        List<String> startup = validator.report().violations();

        List<com.ses.featureenablement.condition.FeatureConditionCheck> checks =
                validator.evaluate(com.ses.config.feature.Feature.NF07_RETENTION, context());

        assertTrue(checks.stream().map(com.ses.featureenablement.condition.FeatureConditionCheck::code).toList()
                .containsAll(startup), () -> "起動時検査の code: " + startup);
        assertTrue(checks.stream().anyMatch(check -> check.code().equals("journal.verifier.unverifiable")),
                "Journal の起動時検査が無ければ評価できない");
        assertEquals(java.util.Set.of(com.ses.config.feature.Feature.NF07_RETENTION,
                com.ses.config.feature.Feature.NF07_DSAR), validator.features());

        UserPermissionGroupMapper broken = mock(UserPermissionGroupMapper.class);
        when(broken.selectEffectivePrivacyAdminUsernames(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("secret-sql-7"));
        PrivacyProductionConfigurationValidator unverifiable = new PrivacyProductionConfigurationValidator(
                flags(true, false), approval(), oidc(Set.of()), permissionGroupMapper, broken, identityProviderMapper,
                CLOCK);
        com.ses.featureenablement.condition.FeatureEvaluation evaluation =
                new com.ses.featureenablement.condition.FeatureConditionRegistry(
                        com.ses.featureenablement.catalog.FeatureEnablementCatalog.standard(), List.of(unverifiable))
                        .evaluate(com.ses.config.feature.Feature.NF07_RETENTION, context());
        assertTrue(evaluation.checks().stream().anyMatch(check -> check.code().equals("privacyAdmins.unverifiable")
                        && check.status() == com.ses.featureenablement.condition.FeatureConditionCheck.Status.UNVERIFIABLE),
                "privacyAdmins.unverifiable は評価できない類");
    }

    /** review P1-2: D-06 の失効は DSAR の評価だけを不成立（時間要因）にし、保持・保全の束縛は成り立つ。 */
    @Test
    void 評価器はD06の失効をDSARだけの時間要因の不成立にする() {
        twoPrivacyAdmins();
        PrivacyApprovalProperties shortD06 = dsarApproval();
        shortD06.getD06().setSignedOn("2025-11-14");
        shortD06.getD06().setExpiresOn("2026-11-14");
        shortD06.setApprovalDigest(PrivacyDecisionBinding.computeDigest(shortD06));
        PrivacyProductionConfigurationValidator validator = dsarValidator(dsarFlags(30, Duration.ofDays(30)), shortD06,
                registryWithProvider());

        List<com.ses.featureenablement.condition.FeatureConditionCheck> dsar =
                validator.evaluate(com.ses.config.feature.Feature.NF07_DSAR, context());
        List<com.ses.featureenablement.condition.FeatureConditionCheck> retention =
                validator.evaluate(com.ses.config.feature.Feature.NF07_RETENTION, context());

        assertTrue(dsar.stream().anyMatch(check -> check.code().equals("decisionBinding.d06.expiresOn.expired")
                && check.temporal()));
        assertTrue(retention.stream().anyMatch(check -> check.code().equals("decisionBinding.valid") && check.passing()),
                "保持・保全は D-06 の失効で閉じない");
        assertTrue(retention.stream().noneMatch(check -> check.code().startsWith("decisionBinding.d06")));
    }

    /** J-17（design §14）: NF07 を DB で管理する間、閉じていて未決の申請も無ければ privacy-admin の人数を判定しない。 */
    @Test
    void 継続性guard向けの判定はNF07が閉じていれば判定せず開いていれば判定し読めなければ確認できない() {
        twoPrivacyAdmins();
        PrivacyProductionConfigurationValidator validator = validator(approval());
        PrivacyEnablementStateReader reader = mock(PrivacyEnablementStateReader.class);
        validator.setEnablementStateReader(reader);
        com.ses.config.AdministratorConditionCheck.Condition condition =
                com.ses.config.AdministratorConditionCheck.Condition.PRIVACY_ADMINISTRATORS;

        when(reader.read()).thenReturn(PrivacyEnablementStateReader.Result.INACTIVE);
        assertEquals(List.of(com.ses.config.AdministratorConditionCheck.notEvaluated(condition, 2)),
                validator.checkAdministratorConditions());
        when(reader.read()).thenReturn(PrivacyEnablementStateReader.Result.ACTIVE);
        assertEquals(List.of(com.ses.config.AdministratorConditionCheck.evaluated(condition, 2, 2, true)),
                validator.checkAdministratorConditions());
        when(reader.read()).thenReturn(PrivacyEnablementStateReader.Result.UNREADABLE);
        assertEquals(List.of(com.ses.config.AdministratorConditionCheck.unverifiable(condition, 2)),
                validator.checkAdministratorConditions());
        when(reader.read()).thenReturn(PrivacyEnablementStateReader.Result.NOT_DATABASE_MANAGED);
        assertEquals(List.of(com.ses.config.AdministratorConditionCheck.evaluated(condition, 2, 2, true)),
                validator.checkAdministratorConditions());
    }

    private static com.ses.featureenablement.condition.FeatureEvaluationContext context() {
        return new com.ses.featureenablement.condition.FeatureEvaluationContext("default",
                PrivacyDecisionBinding.businessDate(CLOCK), CLOCK,
                com.ses.featureenablement.FeatureEnablementTestSnapshots.builder(CLOCK.instant()).build(),
                com.ses.featureenablement.condition.FeatureEvaluationContext.Purpose.PERIODIC, Set.of(), false);
    }
    private void twoPrivacyAdmins() {
        member(admin("nf07b-ops-d"), privacyAdminGroupId);
        member(admin("nf07b-ops-e"), privacyAdminGroupId);
    }

    private void assertInsufficient(int current) {
        IllegalStateException error = blocked(validator(approval()));
        assertContainsAll(error, "privacyAdmins.insufficient[current=" + current + ";required=2]");
    }

    /**
     * FE-C05: prod でも機能の条件では起動を止めない（{@code validate} は log だけ）。条件を満たさないことと違反の固定コードを
     * {@link PrivacyProductionConfigurationValidator#report()} で確かめる（説明の文は起動時の WARN と同じ）。
     */
    private static IllegalStateException blocked(PrivacyProductionConfigurationValidator validator) {
        assertDoesNotThrow(validator::validate, "機能の条件では起動を止めない");
        PrivacyProductionConfigurationValidator.Report report = validator.report();
        assertFalse(report.satisfied(), () -> "条件を満たさないはず: " + report.message());
        return new IllegalStateException(report.message());
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
                permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, CLOCK);
    }

    private PrivacyProductionConfigurationValidator dsarValidator(PrivacyFeatureProperties flags,
                                                                  PrivacyApprovalProperties approval,
                                                                  ProductionPrivacyProviderRegistry registry) {
        return new PrivacyProductionConfigurationValidator(flags, approval, oidc(Set.of()), permissionGroupMapper,
                userPermissionGroupMapper, identityProviderMapper, registry, CLOCK);
    }

    private static PrivacyFeatureProperties dsarFlags(Integer dueDays, Duration packageMaxAge) {
        PrivacyFeatureProperties properties = flags(true, true);
        properties.getDsar().setDueDays(dueDays);
        properties.getDsar().setPackageMaxAge(packageMaxAge);
        return properties;
    }

    /** retention と dsar の束縛（D-01・D-06・D-09）。 */
    private static PrivacyApprovalProperties dsarApproval() {
        PrivacyApprovalProperties both = approval();
        both.setCapabilities(new ArrayList<>(List.of("retention", "dsar")));
        both.getD06().setDecisionId("D06-NF07-2026-0006");
        both.getD06().setSignedOn("2026-11-05");
        both.getD06().setExpiresOn("2027-11-04");
        both.setApprovalDigest(PrivacyDecisionBinding.computeDigest(both));
        return both;
    }

    /** 1件の F2 provider を登録した registry（DB には触れない fixture）。 */
    private static ProductionPrivacyProviderRegistry registryWithProvider() {
        return new ProductionPrivacyProviderRegistry(List.of(new ProductionPrivacyProvider() {
            @Override
            public String providerId() {
                return "validator-fixture";
            }

            @Override
            public List<ProviderDataElement> dataElements() {
                return List.of(ProviderDataElement.exact("VALIDATOR_FIXTURE", SubjectAttribution.ATTRIBUTABLE,
                        List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"))));
            }

            @Override
            public List<ProviderTable> tables() {
                return List.of(new ProviderTable("t_engineer", List.of("id"), List.of()));
            }

            @Override
            public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
                return List.of();
            }
        }));
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
        properties.setIssuerUri(ISSUER);
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

    /** OIDC でログインできる管理者（設定 provider に APPROVED の外部identity link を持つ）。 */
    private long admin(String username) {
        long id = user(username, "管理者", TENANT);
        identity(id, providerId, "APPROVED");
        return id;
    }

    private long insertProvider(String tenantId, String providerType, String issuer, int enabled) {
        long id = nextId++;
        jdbcTemplate.update("INSERT INTO m_identity_provider (id, tenant_id, provider_type, issuer_uri, client_id, enabled, "
                + "deleted_flag) VALUES (?, ?, ?, ?, ?, ?, 0)", id, tenantId, providerType, issuer, "nf07b-client", enabled);
        return id;
    }

    private long identity(long userId, long provider, String reviewStatus) {
        long id = nextId++;
        boolean approved = "APPROVED".equals(reviewStatus);
        jdbcTemplate.update("INSERT INTO t_user_external_identity (id, tenant_id, user_id, provider_id, subject, review_status, "
                        + "reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id, TENANT, userId, provider, "nf07b-subject-" + id, reviewStatus,
                approved ? LocalDateTime.of(2026, 11, 1, 10, 0) : null, approved ? 1L : null);
        return id;
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
