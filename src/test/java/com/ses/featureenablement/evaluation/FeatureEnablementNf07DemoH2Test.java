package com.ses.featureenablement.evaluation;

import com.ses.backup.quiesce.BackupQuiesceProductionValidator;
import com.ses.common.exception.BusinessException;
import com.ses.config.AdministratorConditionCheck;
import com.ses.config.LoginUser;
import com.ses.config.ProductionSchemaVersionGuard;
import com.ses.config.ProductionSecurityConfigurationValidator;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.entity.SysUser;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementActors;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.service.FeatureSignOffBundleService;
import com.ses.featureenablement.service.FeatureSignOffService;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.mapper.SysUserMapper;
import com.ses.privacy.production.PrivacyApprovalSource;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.AdministratorContinuityGuard;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import com.ses.service.security.PermissionGroupManagementService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FE-C05 の Demo（feature-enablement-console tasks.md FE-C05、design §2.6.1・§8.5・§14）: {@code prod} の profile の試験の構成で、
 * 合成の D-01・D-06・D-09 を組で登録・確認し、OIDC 連携済みの {@code privacy-admin} 2 名で保持・保全（{@code NF07_RETENTION}）と
 * 本人請求（{@code NF07_DSAR}）を開き、以下を一続きに確かめる。
 *
 * <ul>
 *   <li>{@code AdministratorContinuityGuard} が {@code privacy-admin} を 2 名未満にする解除を拒否する。</li>
 *   <li>D-01 を更新する組（D-06 を引継ぎ）を確認しても両方の開放単位が {@code OPEN} のまま保たれる。</li>
 *   <li>D-06 を失効させると {@code NF07_DSAR} だけが {@code AUTO_CLOSED(SIGN_OFF_EXPIRED)} になり、D-06 の行が日の始まりの処理で
 *       {@code EXPIRED} になった後の次の定期の評価でも {@code NF07_RETENTION} は {@code OPEN} のまま保たれる。</li>
 *   <li>D-06 を 1 件だけ {@code REVOKED} にした後も {@code NF07_RETENTION} は {@code OPEN} のままで {@code NF07_DSAR} だけが閉じる。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.config.import=optional:classpath:application-test.yml",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:fe_nf07_prod_demo;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL",
        "app.backup-quiesce.enabled=false",
        "app.security.oidc.tenant-id=default",
        "app.security.oidc.issuer-uri=https://idp.invalid/fe-nf07-demo",
        "app.security.oidc.authorization-uri=https://idp.invalid/authorize",
        "app.security.oidc.token-uri=https://idp.invalid/token",
        "app.security.oidc.jwk-set-uri=https://idp.invalid/jwks",
        "app.security.oidc.user-info-uri=https://idp.invalid/userinfo",
        "app.security.oidc.client-id=fe-nf07-demo-client",
        "app.security.oidc.client-secret=fe-nf07-demo-secret",
        "app.security.oidc.break-glass-usernames=admin,breakglass2",
        "app.security.mfa.encryption-key=test-production-mfa-encryption-key-0001",
        "app.security.session.hash-key=test-production-session-hash-key-0001",
        "app.security.trusted-proxies=10.0.0.0/24",
        "app.digital-invoice.provider=none",
        "app.batch.token-secret=test-batch-token-hmac-secret",
        "compliance.gate.credential-crypto.current-key-version=v1",
        "compliance.gate.credential-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "compliance.gate.fingerprint-keys.default.current-key-version=v1",
        "compliance.gate.fingerprint-keys.default.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "certification.number-crypto.current-key-version=v1",
        "certification.number-crypto.keys.v1=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE",
        "privacy.retention.enabled=false",
        "privacy.dsar.enabled=false",
        "privacy.journal.datasource.url=jdbc:mysql://journal.invalid:3306/privacy_journal",
        "privacy.journal.datasource.username=nf07_journal_appender",
        "privacy.journal.datasource.password=synthetic-test-only-journal-password",
        "privacy.journal.journal-id=synthetic-journal-001"
})
@ActiveProfiles("prod")
@Import(FeatureEnablementTestConfiguration.class)
@org.springframework.test.context.jdbc.Sql(scripts = "/sql/permission-group-seed-h2.sql")
@Transactional(isolation = Isolation.READ_COMMITTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeatureEnablementNf07DemoH2Test {

    private static final String TENANT = FeatureEnablementH2Fixture.TENANT;
    private static final String ISSUER = "https://idp.invalid/fe-nf07-demo";
    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);
    private static final Path UPLOAD_DIR = createTempUploadDir();

    @DynamicPropertySource
    static void registerUploadPath(DynamicPropertyRegistry registry) {
        registry.add("app.upload.base-path", () -> UPLOAD_DIR.toAbsolutePath().toString());
    }

    @MockBean
    private ClientRegistrationRepository clientRegistrationRepository;
    @MockBean
    private ProductionSecurityConfigurationValidator productionSecurityConfigurationValidator;
    @MockBean
    private ProductionSchemaVersionGuard productionSchemaVersionGuard;
    @MockBean
    private BackupQuiesceProductionValidator backupQuiesceProductionValidator;
    @MockBean
    private PrivacyJournalStartupVerifier journalVerifier;
    @MockBean
    private FileScanner fileScanner;

    @Autowired
    private FeatureSignOffBundleService bundles;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementEvaluationService evaluation;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureSwitches switches;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private PermissionGroupManagementService permissionGroupManagementService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private long providerId;
    private long roleAdminGroupId;
    private long privacyAdminGroupId;
    private FeatureEnablementActor first;
    private FeatureEnablementActor second;

    @BeforeEach
    void setUp() {
        clock.reset();
        PrivacyJournalStartupVerifier.Result available = new PrivacyJournalStartupVerifier.Result(
                PrivacyJournalStartupVerifier.Availability.AVAILABLE, List.of());
        when(journalVerifier.reverify()).thenReturn(available);
        when(journalVerifier.enablementResult()).thenReturn(available);
        when(journalVerifier.protectionResult()).thenReturn(available);
        when(fileScanner.scan(any(), any())).thenReturn(FileScanResult.clean("synthetic-clean"));
        when(productionSecurityConfigurationValidator.checkAdministratorConditions()).thenReturn(List.of(
                AdministratorConditionCheck.notEvaluated(AdministratorConditionCheck.Condition.OIDC_ADMINISTRATORS, 3),
                AdministratorConditionCheck.notEvaluated(
                        AdministratorConditionCheck.Condition.BREAK_GLASS_ADMINISTRATORS, 2)));

        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        jdbc.update("INSERT INTO m_identity_provider (tenant_id, provider_type, issuer_uri, client_id, enabled, "
                + "deleted_flag) VALUES (?, 'OIDC', ?, 'fe-nf07-demo-client', 1, 0)", TENANT, ISSUER);
        providerId = jdbc.queryForObject("SELECT id FROM m_identity_provider WHERE tenant_id = ? AND issuer_uri = ?",
                Long.class, TENANT, ISSUER);
        roleAdminGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = ? AND group_key = 'role-admin'",
                Long.class, TENANT);
        privacyAdminGroupId = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = ? AND group_key = 'privacy-admin'",
                Long.class, TENANT);

        first = linkedPrivacyAdmin("fe-nf07-pa-1");
        second = linkedPrivacyAdmin("fe-nf07-pa-2");
        AccountingTenantContextHolder.setTenantId(TENANT);
        SecurityContextHolder.getContext().setAuthentication(authentication(first));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @AfterTransaction
    void restoreSharedState() {
        jdbc.update("DELETE FROM t_audit_log WHERE application_code = ?",
                AdministratorContinuityGuard.AUDIT_APPLICATION_CODE);
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        overlay.clearAll(FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        clock.reset();
        holder.reload();
    }

    @AfterAll
    static void cleanTempUploadDir() {
        if (!Files.exists(UPLOAD_DIR)) {
            return;
        }
        try (var paths = Files.walk(UPLOAD_DIR)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    void prod構成でNF07の組と署名を登録確認して2件を開き継続性guardと組の更新とD06の失効および1件取消しを確かめる() {
        // 0. NF07 が閉じていて未決の申請も無い間は、privacy-admin 1 名の解除を継続性 guard が止めない（戻してから本編へ進む）
        Authentication secondAuth = authentication(second);
        SecurityContextHolder.getContext().setAuthentication(secondAuth);
        permissionGroupManagementService.replaceAssignments(first.userId(), Set.of(roleAdminGroupId), secondAuth);
        permissionGroupManagementService.replaceAssignments(first.userId(), Set.of(privacyAdminGroupId), secondAuth);
        SecurityContextHolder.getContext().setAuthentication(authentication(first));

        // 1. 合成の D-01・D-06・D-09 を組で登録・確認し、NF07 の証拠の署名を揃える
        String bundleKind = FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING;
        Nf07BundleSpec initial = new Nf07BundleSpec("D01-NF07-2026-0001", "D06-NF07-2026-0006", "D09-NF07-2026-0009", EXPIRES);
        FeatureSignOffBundle bundle1 = bundles.register(bundleKind, List.of(initial.d01(), initial.d06(), initial.d09()),
                Map.of(), first);
        bundles.confirm(bundle1.getId(), 0, second);
        registerAndConfirmEvidence("NF07_BRIDGE", "TECH_LEAD");
        registerAndConfirmEvidence("NF07_DSAR_HOLD_ACCEPTANCE", "D05_OWNER");
        registerAndConfirmEvidence("NF07_DSAR_SELF_DELIVERY", "D06_OWNER");
        registerAndConfirmEvidence("NF07_DSAR_I10", "PRIVACY");
        registerAndConfirmEvidence("NF07_DSAR_REDACTION", "D06_OWNER");
        holder.reload();

        // 2. privacy-admin 2 名で保持・保全と DSAR を申請・承認して開く
        open(Feature.NF07_RETENTION);
        open(Feature.NF07_DSAR);
        assertThat(switches.isEnabled(Feature.NF07_RETENTION)).isTrue();
        assertThat(switches.isEnabled(Feature.NF07_DSAR)).isTrue();

        // 3. D-01 を更新する組（D-06 を引継ぎ）を確認しても両方が OPEN のまま保たれる
        long keptD06 = fixture.confirmedId("NF07_D06");
        Nf07BundleSpec renewedD01 = initial.withD01("D01-NF07-2027-0002");
        FeatureSignOffBundle bundle2 = bundles.register(bundleKind, List.of(renewedD01.d01(), renewedD01.d09()),
                Map.of("NF07_D06", keptD06), first);
        bundles.confirm(bundle2.getId(), 0, second);
        holder.reload();
        assertThat(fixture.state(Feature.NF07_RETENTION)).isEqualTo("OPEN");
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("OPEN");
        assertThat(switches.isEnabled(Feature.NF07_RETENTION)).isTrue();
        assertThat(switches.isEnabled(Feature.NF07_DSAR)).isTrue();

        // 4. D-06 を翌日失効の組へ差し替えて clock を進めると DSAR だけが閉じ、EXPIRED 後の次の定期評価でも保持・保全は OPEN のまま
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
        Nf07BundleSpec shortD06 = new Nf07BundleSpec("D01-NF07-2027-0003", "D06-NF07-2026-SHORT", "D09-NF07-2027-0009",
                today.plusDays(1));
        FeatureSignOffBundle bundle3 = bundles.register(bundleKind, List.of(shortD06.d01(), shortD06.d06(), shortD06.d09()),
                Map.of(), first);
        bundles.confirm(bundle3.getId(), 0, second);
        long shortD06Id = fixture.confirmedId("NF07_D06");
        holder.reload();
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("OPEN");

        clock.advance(Duration.ofDays(1));
        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF07_DSAR)).isEqualTo("SIGN_OFF_EXPIRED");
        assertThat(fixture.state(Feature.NF07_RETENTION)).as("D-06 の失効では保持・保全を閉じない").isEqualTo("OPEN");

        evaluation.evaluateAtBusinessDayStart();
        assertThat(jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, shortD06Id))
                .isEqualTo("EXPIRED");
        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        holder.reload();
        assertThat(fixture.state(Feature.NF07_RETENTION)).as("D-06 が EXPIRED になった後の定期評価でも OPEN").isEqualTo("OPEN");
        assertThat(switches.isEnabled(Feature.NF07_RETENTION)).isTrue();
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("AUTO_CLOSED");

        // 5. 有効な D-06 を含む組へ更新して DSAR を開き直し、D-06 を 1 件だけ REVOKED にしても保持・保全は OPEN のままで DSAR だけが閉じる
        Nf07BundleSpec reopenedSpec = new Nf07BundleSpec("D01-NF07-2027-0004", "D06-NF07-2027-0006", "D09-NF07-2027-0010",
                EXPIRES);
        FeatureSignOffBundle bundle4 = bundles.register(bundleKind,
                List.of(reopenedSpec.d01(), reopenedSpec.d06(), reopenedSpec.d09()), Map.of(), first);
        bundles.confirm(bundle4.getId(), 0, second);
        holder.reload();
        open(Feature.NF07_DSAR);
        long d06ToRevoke = fixture.confirmedId("NF07_D06");
        int d06Ver = jdbc.queryForObject("SELECT version FROM t_feature_sign_off WHERE id = ?", Integer.class, d06ToRevoke);

        signOffs.revoke(d06ToRevoke, d06Ver, "SIGNER_WITHDREW", second);
        holder.reload();
        assertThat(jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, d06ToRevoke))
                .isEqualTo("REVOKED");
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF07_DSAR)).isEqualTo("SIGN_OFF_REVOKED");
        assertThat(fixture.state(Feature.NF07_RETENTION)).isEqualTo("OPEN");

        evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        holder.reload();
        assertThat(fixture.state(Feature.NF07_RETENTION)).isEqualTo("OPEN");
        assertThat(switches.isEnabled(Feature.NF07_RETENTION)).isTrue();
        assertThat(switches.isEnabled(Feature.NF07_DSAR)).isFalse();

        // 6. NF07_RETENTION が OPEN の間は、別の管理者による privacy-admin 1 名の解除を AdministratorContinuityGuard が拒否する
        // （guard が外側の試験 transaction を rollback-only にするため最後に確かめる）
        SecurityContextHolder.getContext().setAuthentication(secondAuth);
        BusinessException continuityRejected = catchThrowableOfType(
                () -> permissionGroupManagementService.replaceAssignments(first.userId(), Set.of(roleAdminGroupId),
                        secondAuth),
                BusinessException.class);
        assertThat(continuityRejected).isNotNull();
        assertThat(continuityRejected.getCode()).isEqualTo(400);
        assertThat(continuityRejected.getMessageKey()).isEqualTo("error.user.adminContinuity.privacyAdministrators");
        assertThat(continuityRejected.getArgs()).containsExactly(1, 2);
    }

    private void open(Feature feature) {
        FeatureEnablementRequest req = service.requestOpen(feature, null, first);
        assertThat(service.approve(req.getId(), 0, null, second).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private void registerAndConfirmEvidence(String kindCode, String role) {
        var reg = signOffs.register(new FeatureSignOffService.Registration(kindCode, "REF-" + kindCode + "-2026",
                role, "署名 太郎", SIGNED, EXPIRES, Map.of()), pdf(kindCode), kindCode + ".pdf", first);
        signOffs.confirm(reg.getId(), 0, second);
    }

    private FeatureEnablementActor linkedPrivacyAdmin(String username) {
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, real_name, role, status, failed_count, "
                + "deleted_flag) VALUES (?, ?, 'unused', ?, '管理者', 1, 0, 0)", TENANT, username, username);
        Long id = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, 1, 0)", TENANT, id, privacyAdminGroupId);
        jdbc.update("INSERT INTO t_user_external_identity (tenant_id, user_id, provider_id, subject, review_status, "
                        + "reviewed_at, reviewed_by, deleted_flag) VALUES (?, ?, ?, ?, 'APPROVED', ?, 1, 0)",
                TENANT, id, providerId, "sub-" + username, LocalDateTime.of(2026, 11, 1, 10, 0));
        return FeatureEnablementActors.of(id, username);
    }

    private Authentication authentication(FeatureEnablementActor actor) {
        SysUser user = sysUserMapper.selectById(actor.userId());
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    private static byte[] pdf(String marker) {
        return ("%PDF-1.7\n1 0 obj<<>>endobj\n% " + marker + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static Path createTempUploadDir() {
        try {
            return Files.createTempDirectory("fe-nf07-demo-uploads-");
        } catch (Exception e) {
            throw new IllegalStateException("一時 upload directory を作成できません", e);
        }
    }

    private record Nf07BundleSpec(String d01Id, String d06Id, String d09Id, LocalDate d06Expires) {

        Nf07BundleSpec withD01(String newD01Id) {
            return new Nf07BundleSpec(newD01Id, d06Id, d09Id, d06Expires);
        }

        String digest() {
            Map<String, PrivacyApprovalSource.SignOffValue> rows = new LinkedHashMap<>();
            rows.put("NF07_D01", new PrivacyApprovalSource.SignOffValue(d01Id, SIGNED, EXPIRES, Map.of()));
            rows.put("NF07_D06", new PrivacyApprovalSource.SignOffValue(d06Id, SIGNED, d06Expires, Map.of()));
            rows.put("NF07_D09", new PrivacyApprovalSource.SignOffValue(d09Id, SIGNED, EXPIRES,
                    Map.of("tenantId", TENANT, "capabilities", "dsar,retention")));
            return PrivacyDecisionBinding.computeDigest(PrivacyApprovalSource.fromSignOffs(rows, 30));
        }

        FeatureSignOffBundleService.NewMember d01() {
            return member("NF07_D01", d01Id, "PRIVACY_OWNER", EXPIRES, Map.of());
        }

        FeatureSignOffBundleService.NewMember d06() {
            return member("NF07_D06", d06Id, "D06_OWNER", d06Expires, Map.of("dueDays", "30", "packageMaxAge", "30"));
        }

        FeatureSignOffBundleService.NewMember d09() {
            return member("NF07_D09", d09Id, "D09_SIGNER", EXPIRES,
                    Map.of("tenantId", TENANT, "capabilities", "dsar,retention", "approvalDigest", digest()));
        }

        private static FeatureSignOffBundleService.NewMember member(String kind, String ref, String role,
                                                                    LocalDate expiresOn, Map<String, String> structured) {
            return new FeatureSignOffBundleService.NewMember(new FeatureSignOffService.Registration(kind, ref, role,
                    "署名 太郎", SIGNED, expiresOn, structured), pdf(ref), kind + ".pdf");
        }
    }
}
