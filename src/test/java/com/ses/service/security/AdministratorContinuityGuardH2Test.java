package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.AdministratorStartupConditions;
import com.ses.config.LoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.ProductionSecurityConfigurationValidator;
import com.ses.entity.SysUser;
import com.ses.mapper.IdentityProviderMapper;
import com.ses.mapper.PermissionGroupMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserExternalIdentityMapper;
import com.ses.mapper.UserMfaMapper;
import com.ses.mapper.UserPermissionGroupMapper;
import com.ses.privacy.production.PrivacyProductionConfigurationValidator;
import com.ses.service.AuditLogService;
import com.ses.service.SysUserService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;

/**
 * RB-ADMIN-GUARD: 実際の利用者管理・権限group割当のserviceと、実際の起動時検査（{@link ProductionSecurityConfigurationValidator}・
 * {@link PrivacyProductionConfigurationValidator}。H2の実mapperで数える）をつないで、本番の判定では起動時検査を満たさなくなる
 * 無効化・削除・role変更・username変更・privacy-adminの解除を拒否し、prod以外では従来どおり通すことを確かめる。
 *
 * <p>起動時検査は {@code @Profile("prod")} のため、この試験では同じクラスを試験用の設定（fixtureのtenant・issuer・
 * break-glass username）で作り、{@link AdministratorStartupConditions} として登録する（起動時の {@code validate} は呼ばない）。
 * prodの判定（{@code Environment#acceptsProfiles}）だけを {@code isActive} の差替えで切り替える。数えるのはfixtureの
 * providerにlinkした管理者と、fixtureのbreak-glass usernameだけ（seedの管理者は数えない）。拒否の監査はtransactionの完了後に
 * 書くため、監査serviceはmockにしてcommitする行を作らない（rollback・lockの直列化・監査の行は実MySQLの
 * {@code AdministratorContinuityGuardMySqlTest} が見る）。</p>
 *
 * <p>serviceは試験のtransactionへ参加するため、試験のtransactionを加防護のwriterと同じ READ_COMMITTED で始める
 * （guardは実際のtransactionの分離レベルを確かめ、READ_COMMITTED でなければ確認できないとして拒否する）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/permission-group-seed-h2.sql")
@Transactional(isolation = Isolation.READ_COMMITTED)
class AdministratorContinuityGuardH2Test {

    private static final String TENANT = "default";
    private static final String ISSUER = "https://idp.example.invalid/rb-admin-guard-h2";
    private static final String BREAK_GLASS_A = "rb-guard-h2-bg-a";
    private static final String BREAK_GLASS_B = "rb-guard-h2-bg-b";
    /** permission-group-seed-h2.sql の role-admin・privacy-admin。 */
    private static final long ROLE_ADMIN_GROUP = 1L;
    private static final long PRIVACY_ADMIN_GROUP = 6L;
    private static final long BASE = 9_726_000L;

    @SpyBean
    private AdministratorContinuityGuard guard;
    @MockBean
    private AuditLogService auditLogService;
    @Autowired
    private SysUserService sysUserService;
    @Autowired
    private PermissionGroupManagementService permissionGroupManagementService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long nextId;
    private long providerId;
    private SysUser operator;

    /** guard 経由の起動時検査が読む NF07 の開放状態の部品（J-17。試験ごとに戻す）。 */
    private static final java.util.concurrent.atomic.AtomicReference<com.ses.privacy.production.PrivacyEnablementStateReader>
            STATE_READER = new java.util.concurrent.atomic.AtomicReference<>();

    @Autowired
    private com.ses.mapper.FeatureEnablementStateMapper featureStateMapper;
    @Autowired
    private com.ses.mapper.FeatureEnablementRequestMapper featureRequestMapper;

    @TestConfiguration
    static class StartupConditionsConfiguration {

        @Bean
        AdministratorStartupConditions securityStartupConditions(SysUserMapper sysUserMapper,
                                                                 UserMfaMapper userMfaMapper,
                                                                 PermissionGroupMapper permissionGroupMapper,
                                                                 IdentityProviderMapper identityProviderMapper,
                                                                 UserExternalIdentityMapper userExternalIdentityMapper) {
            ProductionSecurityConfigurationValidator validator = new ProductionSecurityConfigurationValidator(
                    conditionProperties(), null, null, sysUserMapper, userMfaMapper, permissionGroupMapper, null, null);
            validator.setIdentityProviderMapper(identityProviderMapper);
            validator.setUserExternalIdentityMapper(userExternalIdentityMapper);
            return validator::checkAdministratorConditions;
        }

        @Bean
        AdministratorStartupConditions privacyStartupConditions(PermissionGroupMapper permissionGroupMapper,
                                                                UserPermissionGroupMapper userPermissionGroupMapper,
                                                                IdentityProviderMapper identityProviderMapper) {
            PrivacyProductionConfigurationValidator validator = new PrivacyProductionConfigurationValidator(null, null,
                    conditionProperties(), permissionGroupMapper, userPermissionGroupMapper, identityProviderMapper, null);
            // J-17 の試験が NF07 の開放状態を読む部品を差し替える（既定は無し = NF07 を DB で管理しない従来の判定）
            return () -> {
                validator.setEnablementStateReader(STATE_READER.get());
                return validator.checkAdministratorConditions();
            };
        }

        private static OidcSecurityProperties conditionProperties() {
            OidcSecurityProperties properties = new OidcSecurityProperties();
            properties.setTenantId(TENANT);
            properties.setIssuerUri(ISSUER);
            properties.setBreakGlassUsernames(new LinkedHashSet<>(List.of(BREAK_GLASS_A, BREAK_GLASS_B)));
            return properties;
        }
    }

    @BeforeEach
    void setUp() {
        doReturn(true).when(guard).isActive();
        nextId = BASE;
        providerId = nextId++;
        jdbc.update("INSERT INTO m_identity_provider (id, tenant_id, provider_type, issuer_uri, client_id, enabled, "
                + "deleted_flag) VALUES (?, ?, 'OIDC', ?, 'rb-guard-h2-client', 1, 0)", providerId, TENANT, ISSUER);
        operator = sysUserMapper.selectById(user("rb-guard-h2-operator", "管理者"));
        AccountingTenantContextHolder.setTenantId(TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication());
    }

    @AfterEach
    void tearDown() {
        STATE_READER.set(null);
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 本番では無効化でOIDC管理者が3名を割るなら拒否し割らない無効化は通す() {
        long first = linkedAdmin("ops-a");
        long second = linkedAdmin("ops-b");
        linkedAdmin("ops-c");
        linkedAdmin("ops-d");

        assertDoesNotThrow(() -> sysUserService.updateUserStatus(first, 0, authentication()));
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.updateUserStatus(second, 0, authentication()));

        assertRejected(rejected, "error.user.adminContinuity.oidcAdministrators", 2, 3);
    }

    @Test
    void prod以外では起動時検査を割る無効化も従来どおり通す() {
        doReturn(false).when(guard).isActive();
        long first = linkedAdmin("ops-a");
        linkedAdmin("ops-b");
        linkedAdmin("ops-c");

        assertDoesNotThrow(() -> sysUserService.updateUserStatus(first, 0, authentication()));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, first));
    }

    @Test
    void prod以外では起動時検査を割る削除とrole変更も従来どおり通す() {
        doReturn(false).when(guard).isActive();
        long deleted = linkedAdmin("ops-a");
        long demoted = linkedAdmin("ops-b");
        linkedAdmin("ops-c");
        SysUser body = new SysUser();
        body.setRole("マネージャー");

        assertDoesNotThrow(() -> sysUserService.deleteUser(deleted, authentication()));
        assertDoesNotThrow(() -> sysUserService.updateUser(demoted, body, authentication()));

        assertEquals(1, jdbc.queryForObject("SELECT deleted_flag FROM sys_user WHERE id = ?", Integer.class, deleted));
        assertEquals("マネージャー", jdbc.queryForObject("SELECT role FROM sys_user WHERE id = ?", String.class, demoted));
    }

    @Test
    void 本番では削除でOIDC管理者が3名を割るなら拒否する() {
        long first = linkedAdmin("ops-a");
        linkedAdmin("ops-b");
        linkedAdmin("ops-c");

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.deleteUser(first, authentication()));

        assertRejected(rejected, "error.user.adminContinuity.oidcAdministrators", 2, 3);
    }

    @Test
    void 本番ではrole変更でOIDC管理者が3名を割るなら拒否する() {
        long first = linkedAdmin("ops-a");
        linkedAdmin("ops-b");
        linkedAdmin("ops-c");
        SysUser body = new SysUser();
        body.setRole("マネージャー");

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.updateUser(first, body, authentication()));

        assertRejected(rejected, "error.user.adminContinuity.oidcAdministrators", 2, 3);
    }

    @Test
    void 本番ではbreak_glass管理者のusername変更は起動時検査の照合を外すため拒否する() {
        long breakGlass = breakGlassAdmin(BREAK_GLASS_A);
        breakGlassAdmin(BREAK_GLASS_B);
        SysUser body = new SysUser();
        body.setUsername(BREAK_GLASS_A + "-renamed");

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.updateUser(breakGlass, body, authentication()));

        assertRejected(rejected, "error.user.adminContinuity.breakGlassAdministrators", 1, 2);
    }

    @Test
    void 本番ではprivacy_adminの解除で2名を割るなら拒否する() {
        // role-admin は privacy.* を拒否する（V191）ため、privacy管理者は privacy-admin だけに所属する。
        long first = linkedAdmin("privacy-a", PRIVACY_ADMIN_GROUP);
        linkedAdmin("privacy-b", PRIVACY_ADMIN_GROUP);

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> permissionGroupManagementService.replaceAssignments(first, Set.of(ROLE_ADMIN_GROUP),
                        authentication()));

        assertRejected(rejected, "error.user.adminContinuity.privacyAdministrators", 1, 2);
    }

    /**
     * J-17（design §14。review rb-fe-c P2-4）: NF07 を DB の開放状態で管理する間、保持・保全と DSAR が閉じていて未決の申請も無ければ、
     * privacy-admin を2名未満にする解除を拒否しない。開いている・未決の申請がある時は拒否し、開放状態を読めなければ確認できない
     * として拒否する（実際の guard の READ_COMMITTED の transaction と実 SQL で確かめる）。
     */
    @Test
    void 本番でNF07が閉じていればprivacy_adminの解除を拒否せず開いていれば拒否する() {
        STATE_READER.set(databaseReader(featureStateMapper));
        long first = linkedAdmin("privacy-c", PRIVACY_ADMIN_GROUP);
        long second = linkedAdmin("privacy-d", PRIVACY_ADMIN_GROUP);

        assertDoesNotThrow(() -> permissionGroupManagementService.replaceAssignments(first, Set.of(ROLE_ADMIN_GROUP),
                authentication()), "NF07 が閉じていて未決の申請も無ければ判定しない");

        linkedAdmin("privacy-e", PRIVACY_ADMIN_GROUP);
        openNf07("NF07_RETENTION");
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> permissionGroupManagementService.replaceAssignments(second, Set.of(ROLE_ADMIN_GROUP),
                        authentication()));
        assertRejected(rejected, "error.user.adminContinuity.privacyAdministrators", 1, 2);
    }

    @Test
    void 本番でNF07に未決の申請があればprivacy_adminの解除を拒否する() {
        STATE_READER.set(databaseReader(featureStateMapper));
        long first = linkedAdmin("privacy-f", PRIVACY_ADMIN_GROUP);
        linkedAdmin("privacy-g", PRIVACY_ADMIN_GROUP);
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, version) VALUES "
                        + "(?, 'OPEN', 'NF07_DSAR', 'PENDING', ?, 'fixture', ?, TIMESTAMP '2026-11-23 09:00:00', "
                        + "TIMESTAMP '2026-11-30 09:00:00', 0)", TENANT, "e".repeat(64), operator.getId());

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> permissionGroupManagementService.replaceAssignments(first, Set.of(ROLE_ADMIN_GROUP),
                        authentication()));
        assertRejected(rejected, "error.user.adminContinuity.privacyAdministrators", 1, 2);
    }

    @Test
    void 本番でNF07の開放状態を読めなければ利用者の管理の変更を確認できないとして拒否する() {
        com.ses.mapper.FeatureEnablementStateMapper broken = org.mockito.Mockito.mock(
                com.ses.mapper.FeatureEnablementStateMapper.class);
        org.mockito.Mockito.when(broken.selectByTenant(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("io"));
        STATE_READER.set(databaseReader(broken));
        long first = linkedAdmin("ops-u");
        linkedAdmin("ops-v");
        linkedAdmin("ops-w");
        linkedAdmin("ops-x");

        BusinessException rejected = assertThrows(BusinessException.class,
                () -> sysUserService.updateUserStatus(first, 0, authentication()));
        assertEquals(409, rejected.getCode());
        assertEquals(AdministratorContinuityGuard.UNVERIFIABLE_MESSAGE_KEY, rejected.getMessageKey());
    }

    /** NF07 を DB の源で読む部品。 */
    private com.ses.privacy.production.PrivacyEnablementStateReader databaseReader(
            com.ses.mapper.FeatureEnablementStateMapper stateMapper) {
        com.ses.featureenablement.config.FeatureEnablementProperties properties =
                new com.ses.featureenablement.config.FeatureEnablementProperties();
        properties.setSource(com.ses.featureenablement.config.FeatureEnablementProperties.Source.DATABASE);
        return new com.ses.privacy.production.PrivacyEnablementStateReader(properties, stateMapper, featureRequestMapper,
                com.ses.featureenablement.state.FeatureEnablementTenant.fixed(TENANT));
    }

    /** NF07 の開放単位を DB で開く（承認した申請と開放状態の行）。 */
    private void openNf07(String featureKey) {
        long approver = user("rb-guard-h2-approver-" + featureKey, "管理者");
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, decided_by, "
                        + "decided_at, version) VALUES (?, 'OPEN', ?, 'APPROVED', ?, 'fixture', ?, TIMESTAMP '2026-11-20 09:00:00', "
                        + "TIMESTAMP '2026-11-27 09:00:00', ?, TIMESTAMP '2026-11-20 10:00:00', 1)", TENANT, featureKey,
                "f".repeat(64), operator.getId(), approver);
        Long requestId = jdbc.queryForObject("SELECT MAX(id) FROM t_feature_enablement_request WHERE tenant_id = ? "
                + "AND target_key = ? AND status = 'APPROVED'", Long.class, TENANT, featureKey);
        jdbc.update("INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, open_request_id, "
                        + "opened_by, approved_by, opened_at, updated_at) VALUES (?, ?, 'OPEN', 1, ?, ?, ?, "
                        + "TIMESTAMP '2026-11-20 10:00:00', TIMESTAMP '2026-11-20 10:00:00')", TENANT, featureKey, requestId,
                operator.getId(), approver);
    }

    @Test
    void 本番でも前から満たさない条件は拒否せず作成も通す() {
        // OIDC管理者は2名（bootstrapで揃えている途中）。break-glass・privacy-adminも前から満たさない。
        long first = linkedAdmin("ops-a");
        linkedAdmin("ops-b");

        assertDoesNotThrow(() -> sysUserService.updateUserStatus(first, 0, authentication()));
        SysUser created = new SysUser();
        created.setUsername("rb-guard-h2-created");
        created.setPassword("Passw0rd123");
        created.setRealName("作成");
        created.setRole("管理者");
        assertDoesNotThrow(() -> sysUserService.createUser(created, authentication()));
    }

    private static void assertRejected(BusinessException rejected, String key, int after, int required) {
        assertEquals(400, rejected.getCode());
        assertEquals(key, rejected.getMessageKey());
        assertArrayEquals(new Object[] {after, required}, rejected.getArgs());
    }

    private Authentication authentication() {
        LoginUser principal = new LoginUser(operator, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    /** fixtureのproviderにAPPROVEDの外部identity linkを持ち、OIDCでログインできる有効な管理者（role-admin所属）。 */
    private long linkedAdmin(String name) {
        return linkedAdmin(name, ROLE_ADMIN_GROUP);
    }

    private long linkedAdmin(String name, long groupId) {
        long id = user("rb-guard-h2-" + name, "管理者");
        member(id, groupId);
        jdbc.update("INSERT INTO t_user_external_identity (tenant_id, user_id, provider_id, subject, review_status, "
                        + "reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, 'APPROVED', ?, 1, 0)",
                TENANT, id, providerId, "rb-guard-h2-subject-" + id, LocalDateTime.of(2026, 11, 1, 10, 0));
        return id;
    }

    /** MFA登録済みの有効な管理者（break-glass username）。 */
    private long breakGlassAdmin(String username) {
        long id = user(username, "管理者");
        member(id, ROLE_ADMIN_GROUP);
        jdbc.update("INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at, "
                + "deleted_flag) VALUES (?, ?, 'v1:rb-guard-h2', 'v1', ?, 0)", TENANT, id, LocalDateTime.of(2026, 11, 1, 10, 0));
        return id;
    }

    private long user(String username, String role) {
        long id = nextId++;
        jdbc.update("INSERT INTO sys_user (id, username, password, real_name, role, status, tenant_id, deleted_flag) "
                + "VALUES (?, ?, 'unused', ?, ?, 1, ?, 0)", id, username, username, role, TENANT);
        return id;
    }

    private void member(long userId, long groupId) {
        jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, 1, 0)", TENANT, userId, groupId);
    }
}
