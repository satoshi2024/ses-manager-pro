package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffBundleMapper;
import com.ses.mapper.FeatureSignOffBundleMemberMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-A12（design §2.6.1・§6.5、review P1-2）: 署名の組を試験用の組の種類（{@code TEST_BUNDLE}: member {@code NF04_BUNDLE_A}・
 * {@code NF04_BUNDLE_B}、NF04 が要する）で確かめる。組の種類に依らない流れ（NF07 の組の整合の検査は FE-C01）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureSignOffBundleH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);
    private static final String A = FeatureEnablementTestConfiguration.TEST_BUNDLE_A;
    private static final String B = FeatureEnablementTestConfiguration.TEST_BUNDLE_B;
    private static final String BUNDLE = FeatureEnablementTestConfiguration.TEST_BUNDLE;

    @Autowired
    private FeatureSignOffBundleService bundles;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementWriteSupport write;
    @Autowired
    private FeatureSignOffTransitions transitions;
    @Autowired
    private FeatureSignOffRegistrar registrar;
    @Autowired
    private FeatureEnablementActorPolicy actorPolicy;
    @Autowired
    private FeatureSignOffMapper signOffMapper;
    @Autowired
    private FeatureSignOffBundleMapper bundleMapper;
    @Autowired
    private FeatureSignOffBundleMemberMapper memberMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor registrant;
    private FeatureEnablementActor confirmer;

    /**
     * 試験の H2 は全ての context で同じ in-memory の DB（jdbc:h2:mem:testdb）を使い、FK の検査の有無は DB の設定である。
     * engineer-schema-h2.sql・schema-mismatch-h2.sql は SET REFERENTIAL_INTEGRITY FALSE にしたまま戻さず、この class の
     * context が先に作られて cache されていると、random の実行順でそれらの試験の後に回った時に初期化の replay
     * （schema-order-acceptance-h2.sql が TRUE に戻す）が走らず、複合 FK が検査されない（CI の run 45147817969）。
     * H2 の SET は実行中の transaction を commit するため、試験の transaction の外（始まる前）で有効にする。
     */
    @org.springframework.test.context.transaction.BeforeTransaction
    void enableReferentialIntegrity() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        registrant = fixture.admin();
        confirmer = fixture.admin();
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
    }

    @Test
    void 組は1回の確認で旧い組と置き換わる署名をSUPERSEDEDにし引き継ぐ署名は確認済みのまま開いたまま() {
        open(Feature.NF04);
        long oldBundle = fixture.confirmedBundleId(BUNDLE);
        long oldA = fixture.confirmedId(A);
        long keptB = fixture.confirmedId(B);

        FeatureSignOffBundle bundle = bundles.register(BUNDLE, List.of(newMember(A, "BO-2026-1120")), Map.of(B, keptB),
                registrant);
        assertThat(bundle.getStatus()).isEqualTo("REGISTERED");
        long newA = memberSignOff(bundle.getId(), A);
        assertThat(status(newA)).isEqualTo("REGISTERED");
        BusinessException self = catchThrowableOfType(() -> bundles.confirm(bundle.getId(), 0, registrant),
                BusinessException.class);
        assertThat(self.getCode()).isEqualTo(403);

        FeatureSignOffBundle confirmed = bundles.confirm(bundle.getId(), 0, confirmer);

        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(bundleStatus(oldBundle)).isEqualTo("SUPERSEDED");
        assertThat(status(oldA)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT superseded_by_id FROM t_feature_sign_off WHERE id = ?", Long.class, oldA))
                .isEqualTo(newA);
        assertThat(status(newA)).isEqualTo("CONFIRMED");
        assertThat(status(keptB)).as("引き継ぐ署名は確認済みのまま").isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT carried_over FROM t_feature_sign_off_bundle_member WHERE bundle_id = ? "
                + "AND kind_code = ?", Integer.class, bundle.getId(), B)).isEqualTo(1);
        assertThat(fixture.state(Feature.NF04)).isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_BUNDLE_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void 組に属する種別の1件ずつの確認は409で組の構成が欠ければ400() {
        FeatureSignOff single = signOffs.register(new FeatureSignOffService.Registration(A, "BO-2026-1121",
                "BUSINESS_OWNER", null, SIGNED, EXPIRES, Map.of()), FeatureSignOffServiceH2Test.pdf("single"), "a.pdf",
                registrant);
        BusinessException bundleOnly = catchThrowableOfType(() -> signOffs.confirm(single.getId(), 0, confirmer),
                BusinessException.class);
        assertThat(bundleOnly.getCode()).isEqualTo(409);
        assertThat(bundleOnly.getMessageKey()).isEqualTo("error.featureEnablement.signOffBundleOnly");
        assertThat(status(single.getId())).isEqualTo("REGISTERED");

        // 必須の member の欠け・組の種類に無い種別・引継ぎだけの組は 400
        assertMembersInvalid(() -> bundles.register(BUNDLE, List.of(newMember(A, "BO-2026-1122")), Map.of(), registrant));
        assertMembersInvalid(() -> bundles.register(BUNDLE, List.of(newMember(A, "BO-2026-1122"),
                newOther("NF04_SRE")), Map.of(B, fixture.confirmedId(B)), registrant));
        assertMembersInvalid(() -> bundles.register(BUNDLE, List.of(), Map.of(A, fixture.confirmedId(A),
                B, fixture.confirmedId(B)), registrant));
        BusinessException unknown = catchThrowableOfType(() -> bundles.register("NF99_BUNDLE", List.of(), Map.of(),
                registrant), BusinessException.class);
        assertThat(unknown.getMessageKey()).isEqualTo("error.featureEnablement.bundleKindUnknown");
    }

    @Test
    void 整合のhookが拒めば組の確認は何も変えない() {
        long oldBundle = fixture.confirmedBundleId(BUNDLE);
        long oldA = fixture.confirmedId(A);
        FeatureSignOffBundle bundle = bundles.register(BUNDLE, List.of(newMember(A, "BO-2026-1123")),
                Map.of(B, fixture.confirmedId(B)), registrant);
        long newA = memberSignOff(bundle.getId(), A);
        FeatureSignOffBundleConsistencyCheck rejecting = new FeatureSignOffBundleConsistencyCheck() {
            @Override
            public String bundleKind() {
                return BUNDLE;
            }

            @Override
            public void verify(String tenantId, Map<String, FeatureSignOff> members) {
                assertThat(members).containsOnlyKeys(A, B);
                throw BusinessException.of(400, "error.featureEnablement.bundleInconsistent", "approvalDigest");
            }
        };

        BusinessException inconsistent = catchThrowableOfType(() -> withChecks(rejecting).confirm(bundle.getId(), 0,
                confirmer), BusinessException.class);

        assertThat(inconsistent.getMessageKey()).isEqualTo("error.featureEnablement.bundleInconsistent");
        assertThat(bundleStatus(bundle.getId())).isEqualTo("REGISTERED");
        assertThat(bundleStatus(oldBundle)).isEqualTo("CONFIRMED");
        assertThat(status(oldA)).isEqualTo("CONFIRMED");
        assertThat(status(newA)).isEqualTo("REGISTERED");
    }

    /** review rb-fe-a1 P3-3: 整合の検査を要する組の種類で検査の実装が無ければ、組の確認は 409 で何も変えない（fail-closed）。 */
    @Test
    void 整合の検査を要する組の種類で検査の実装が無ければ確認は409() {
        long oldBundle = fixture.confirmedBundleId(BUNDLE);
        FeatureSignOffBundle bundle = bundles.register(BUNDLE, List.of(newMember(A, "BO-2026-1124")),
                Map.of(B, fixture.confirmedId(B)), registrant);

        BusinessException missing = catchThrowableOfType(() -> withCatalogAndChecks(
                FeatureEnablementTestConfiguration.testCatalog(true)).confirm(bundle.getId(), 0, confirmer),
                BusinessException.class);

        assertThat(missing.getCode()).isEqualTo(409);
        assertThat(missing.getMessageKey()).isEqualTo("error.featureEnablement.bundleCheckMissing");
        assertThat(bundleStatus(bundle.getId())).isEqualTo("REGISTERED");
        assertThat(bundleStatus(oldBundle)).isEqualTo("CONFIRMED");
        assertThat(FeatureEnablementCatalog.standard().bundleKind(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING)
                .orElseThrow().consistencyCheckRequired()).as("NF07 の組は検査を要する").isTrue();
    }

    @Test
    void 組の取消しで組の種類を要する開放単位が自動閉鎖する() {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        open(Feature.NF04);
        open(Feature.NF03);
        long current = fixture.confirmedBundleId(BUNDLE);

        bundles.revoke(current, 0, "DECISION_WITHDRAWN", confirmer);

        assertThat(bundleStatus(current)).isEqualTo("REVOKED");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_REVOKED");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
        assertThat(status(fixture.confirmedId(A))).as("member の署名の行は変えない").isEqualTo("CONFIRMED");
    }

    @Test
    void memberの1件の取消しでは組は確認済みのままその種別を要する開放単位だけが閉じる() {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        open(Feature.NF04);
        open(Feature.NF03);
        long current = fixture.confirmedBundleId(BUNDLE);
        long memberA = fixture.confirmedId(A);

        signOffs.revoke(memberA, 0, "SIGNER_WITHDREW", confirmer);

        assertThat(status(memberA)).isEqualTo("REVOKED");
        assertThat(bundleStatus(current)).as("組は確認済みのまま").isEqualTo("CONFIRMED");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_REVOKED");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
    }

    @Test
    void memberの種別とtenantは署名の行と複合FKで一致する() {
        long bundle = fixture.bundle(BUNDLE, "REGISTERED", Map.of());
        long sre = fixture.confirmedId("NF04_SRE");

        assertThatThrownBy(() -> jdbc.update("INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, "
                + "kind_code, sign_off_id, carried_over) VALUES (?, 'default', ?, ?, 0)", bundle, A, sre))
                .as("署名の行と違う種別").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO t_feature_sign_off_bundle_member (bundle_id, tenant_id, "
                + "kind_code, sign_off_id, carried_over) VALUES (?, 'tenant-x', 'NF04_SRE', ?, 0)", bundle, sre))
                .as("署名の行と違う tenant").isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- NF07 の組（FE-C01・FE-C05）

    /** FE-C01: D-01 を更新する組は D-06 を引き継いで確認でき、D-09 の digest が組と合わなければ 400 で何も変えない。 */
    @Test
    void NF07の組はD01の更新でD06を引き継ぎD09のdigestの不一致は400() {
        FeatureEnablementActor first = privacyAdmin();
        FeatureEnablementActor second = privacyAdmin();
        String bundleKind = FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING;
        Nf07Values initial = new Nf07Values("D01-NF07-2026-0001", "D06-NF07-2026-0006", "D09-NF07-2026-0009");
        FeatureSignOffBundle created = bundles.register(bundleKind, List.of(initial.d01(), initial.d06(), initial.d09()),
                Map.of(), first);
        BusinessException notPrivacyAdmin = catchThrowableOfType(() -> bundles.confirm(created.getId(), 0,
                fixture.admin()), BusinessException.class);
        assertThat(notPrivacyAdmin.getCode()).as("NF07 の組の確認者は privacy-admin に限る").isEqualTo(403);
        bundles.confirm(created.getId(), 0, second);
        long d06 = fixture.confirmedId("NF07_D06");
        long oldD01 = fixture.confirmedId("NF07_D01");

        // D-09 の digest が組の D-01 と合わない組は 400 で確認しない
        Nf07Values stale = initial.withD01("D01-NF07-2027-0001").withDigestOf(initial);
        FeatureSignOffBundle inconsistent = bundles.register(bundleKind, List.of(stale.d01(), stale.d09()),
                Map.of("NF07_D06", d06), first);
        BusinessException mismatch = catchThrowableOfType(() -> bundles.confirm(inconsistent.getId(), 0, second),
                BusinessException.class);
        assertThat(mismatch.getCode()).isEqualTo(400);
        assertThat(mismatch.getMessageKey()).isEqualTo("error.featureEnablement.bundleInconsistent");
        assertThat(bundleStatus(inconsistent.getId())).isEqualTo("REGISTERED");
        assertThat(fixture.confirmedId("NF07_D01")).isEqualTo(oldD01);
        bundles.withdraw(inconsistent.getId(), 0, first);

        // 正しい digest の組は D-06 を引き継いで確認でき、D-01・D-09 だけが置き換わる
        Nf07Values renewed = initial.withD01("D01-NF07-2027-0002");
        FeatureSignOffBundle renewal = bundles.register(bundleKind, List.of(renewed.d01(), renewed.d09()),
                Map.of("NF07_D06", d06), first);
        bundles.confirm(renewal.getId(), 0, second);
        assertThat(bundleStatus(renewal.getId())).isEqualTo("CONFIRMED");
        assertThat(bundleStatus(created.getId())).isEqualTo("SUPERSEDED");
        assertThat(status(oldD01)).isEqualTo("SUPERSEDED");
        assertThat(status(d06)).as("引き継いだ D-06 は確認済みのまま").isEqualTo("CONFIRMED");
        holder.reload();
        com.ses.privacy.production.PrivacyApprovalSource values =
                com.ses.privacy.production.DatabasePrivacyApprovalSource.of(holder.get(), 30);
        assertThat(values.reference(com.ses.privacy.production.PrivacyDecisionBinding.Decision.D01).decisionId())
                .isEqualTo("D01-NF07-2027-0002");
        assertThat(com.ses.privacy.production.PrivacyDecisionBinding.evaluate(values,
                java.util.EnumSet.allOf(com.ses.privacy.production.PrivacyAccessGuard.Capability.class),
                FeatureEnablementH2Fixture.TENANT, LocalDate.of(2026, 11, 23)).complete()).isTrue();
    }

    /**
     * FE-C05 の Demo の試験（review P2-N1。FE-AC-009-04）: D-06 を1件だけ取り消すと DSAR だけが閉じ、保持・保全は開いたまま。
     * 取り消した D-06 の行（REVOKED）も組の member として snapshot に残り、保持・保全の決定の束縛は成り立ったまま。
     */
    @Test
    void D06を1件だけ取り消すとDSARだけが閉じ保持保全は開いたままで束縛も成り立つ() {
        FeatureEnablementActor first = privacyAdmin();
        FeatureEnablementActor second = privacyAdmin();
        Nf07Values values = new Nf07Values("D01-NF07-2026-0101", "D06-NF07-2026-0106", "D09-NF07-2026-0109");
        FeatureSignOffBundle bundle = bundles.register(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING,
                List.of(values.d01(), values.d06(), values.d09()), Map.of(), first);
        bundles.confirm(bundle.getId(), 0, second);
        fixture.confirmedSignOffs(Feature.NF07_RETENTION, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF07_DSAR, SIGNED, EXPIRES);
        fixture.openDirectly(Feature.NF07_RETENTION, first, second);
        fixture.openDirectly(Feature.NF07_DSAR, first, second);
        long d06 = fixture.confirmedId("NF07_D06");

        BusinessException notPrivacyAdmin = catchThrowableOfType(() -> signOffs.revoke(d06, version(d06),
                "SIGNER_WITHDREW", fixture.admin()), BusinessException.class);
        assertThat(notPrivacyAdmin.getCode()).as("NF07 の署名の取消しは privacy-admin に限る").isEqualTo(403);
        signOffs.revoke(d06, version(d06), "SIGNER_WITHDREW", second);

        assertThat(status(d06)).isEqualTo("REVOKED");
        assertThat(bundleStatus(bundle.getId())).as("組は確認済みのまま").isEqualTo("CONFIRMED");
        assertThat(fixture.state(Feature.NF07_DSAR)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF07_DSAR)).isEqualTo("SIGN_OFF_REVOKED");
        assertThat(fixture.state(Feature.NF07_RETENTION)).isEqualTo("OPEN");
        holder.reload();
        FeatureEnablementSnapshot.BundleMemberView member = holder.get().bundles()
                .get(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING).members().get("NF07_D06");
        assertThat(member.status()).isEqualTo("REVOKED");
        com.ses.privacy.production.PrivacyApprovalSource source =
                com.ses.privacy.production.DatabasePrivacyApprovalSource.of(holder.get(), 30);
        assertThat(com.ses.privacy.production.PrivacyDecisionBinding.evaluate(source,
                java.util.EnumSet.of(com.ses.privacy.production.PrivacyAccessGuard.Capability.RETENTION),
                FeatureEnablementH2Fixture.TENANT, LocalDate.of(2026, 11, 23)).complete())
                .as("取り消した D-06 も組の値として digest に入る").isTrue();
    }

    /** privacy-admin group に所属させた有効な管理者。 */
    private FeatureEnablementActor privacyAdmin() {
        FeatureEnablementActor actor = fixture.admin();
        Long group = jdbc.queryForList("SELECT id FROM m_permission_group WHERE tenant_id = ? AND group_key = "
                + "'privacy-admin' AND enabled = 1 AND deleted_flag = 0", Long.class, FeatureEnablementH2Fixture.TENANT)
                .stream().findFirst().orElse(null);
        if (group == null) {
            jdbc.update("INSERT INTO m_permission_group (tenant_id, group_key, group_name, enabled, deleted_flag) "
                    + "VALUES (?, 'privacy-admin', 'privacy-admin', 1, 0)", FeatureEnablementH2Fixture.TENANT);
            group = jdbc.queryForObject("SELECT id FROM m_permission_group WHERE tenant_id = ? AND group_key = "
                    + "'privacy-admin'", Long.class, FeatureEnablementH2Fixture.TENANT);
        }
        jdbc.update("INSERT INTO t_user_permission_group (tenant_id, user_id, group_id, assigned_by, deleted_flag) "
                + "VALUES (?, ?, ?, 1, 0)", FeatureEnablementH2Fixture.TENANT, actor.userId(), group);
        return actor;
    }

    private int version(long signOffId) {
        return jdbc.queryForObject("SELECT version FROM t_feature_sign_off WHERE id = ?", Integer.class, signOffId);
    }

    /** NF07 の組の新しい署名（D-01・D-06・D-09。D-09 の digest は組の値から計算する）。 */
    private record Nf07Values(String d01Id, String d06Id, String d09Id, String digest) {

        Nf07Values(String d01Id, String d06Id, String d09Id) {
            this(d01Id, d06Id, d09Id, null);
        }

        Nf07Values withD01(String id) {
            return new Nf07Values(id, d06Id, d09Id, null);
        }

        /** 別の値の組の digest を使う（不一致の組）。 */
        Nf07Values withDigestOf(Nf07Values other) {
            return new Nf07Values(d01Id, d06Id, d09Id, other.computedDigest());
        }

        String computedDigest() {
            Map<String, com.ses.privacy.production.PrivacyApprovalSource.SignOffValue> rows = new java.util.LinkedHashMap<>();
            rows.put("NF07_D01", new com.ses.privacy.production.PrivacyApprovalSource.SignOffValue(d01Id, SIGNED, EXPIRES,
                    Map.of()));
            rows.put("NF07_D06", new com.ses.privacy.production.PrivacyApprovalSource.SignOffValue(d06Id, SIGNED, EXPIRES,
                    Map.of()));
            rows.put("NF07_D09", new com.ses.privacy.production.PrivacyApprovalSource.SignOffValue(d09Id, SIGNED, EXPIRES,
                    Map.of("tenantId", FeatureEnablementH2Fixture.TENANT, "capabilities", "dsar,retention")));
            return com.ses.privacy.production.PrivacyDecisionBinding.computeDigest(
                    com.ses.privacy.production.PrivacyApprovalSource.fromSignOffs(rows, 30));
        }

        FeatureSignOffBundleService.NewMember d01() {
            return nf07("NF07_D01", d01Id, "PRIVACY_OWNER", Map.of());
        }

        FeatureSignOffBundleService.NewMember d06() {
            return nf07("NF07_D06", d06Id, "D06_OWNER", Map.of("dueDays", "30", "packageMaxAge", "30"));
        }

        FeatureSignOffBundleService.NewMember d09() {
            return nf07("NF07_D09", d09Id, "D09_SIGNER", Map.of("tenantId", FeatureEnablementH2Fixture.TENANT,
                    "capabilities", "dsar,retention", "approvalDigest", digest == null ? computedDigest() : digest));
        }

        private static FeatureSignOffBundleService.NewMember nf07(String kind, String reference, String role,
                                                                Map<String, String> structured) {
            return new FeatureSignOffBundleService.NewMember(new FeatureSignOffService.Registration(kind, reference,
                    role, null, SIGNED, EXPIRES, structured), FeatureSignOffServiceH2Test.pdf(reference), kind + ".pdf");
        }
    }
    // ---------------------------------------------------------------- helper

    private FeatureSignOffBundleService withChecks(FeatureSignOffBundleConsistencyCheck... checks) {
        return withCatalogAndChecks(catalog, checks);
    }

    private FeatureSignOffBundleService withCatalogAndChecks(FeatureEnablementCatalog bundleCatalog,
                                                             FeatureSignOffBundleConsistencyCheck... checks) {
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSignOffBundleConsistencyCheck> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(checks));
        return new FeatureSignOffBundleServiceImpl(write, transitions, registrar, actorPolicy, bundleCatalog, signOffMapper,
                bundleMapper, memberMapper, FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT), provider);
    }

    private void open(Feature feature) {
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private static FeatureSignOffBundleService.NewMember newMember(String kind, String reference) {
        return new FeatureSignOffBundleService.NewMember(new FeatureSignOffService.Registration(kind, reference,
                A.equals(kind) ? "BUSINESS_OWNER" : "SRE", null, SIGNED, EXPIRES, Map.of()),
                FeatureSignOffServiceH2Test.pdf(reference), kind + ".pdf");
    }

    private static FeatureSignOffBundleService.NewMember newOther(String kind) {
        return new FeatureSignOffBundleService.NewMember(new FeatureSignOffService.Registration(kind, "SRE-2026-1120",
                "SRE", null, SIGNED, EXPIRES, Map.of()), FeatureSignOffServiceH2Test.pdf(kind), kind + ".pdf");
    }

    private long memberSignOff(long bundleId, String kind) {
        return jdbc.queryForObject("SELECT sign_off_id FROM t_feature_sign_off_bundle_member WHERE bundle_id = ? "
                + "AND kind_code = ?", Long.class, bundleId, kind);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, id);
    }

    private String bundleStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM t_feature_sign_off_bundle WHERE id = ?", String.class, id);
    }

    private static void assertMembersInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        BusinessException invalid = catchThrowableOfType(call, BusinessException.class);
        assertThat(invalid).isNotNull();
        assertThat(invalid.getCode()).isEqualTo(400);
        assertThat(invalid.getMessageKey()).isEqualTo("error.featureEnablement.bundleMembersInvalid");
    }
}
