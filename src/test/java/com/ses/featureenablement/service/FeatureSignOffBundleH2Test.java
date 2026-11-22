package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffBundle;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
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

    // ---------------------------------------------------------------- helper

    private FeatureSignOffBundleService withChecks(FeatureSignOffBundleConsistencyCheck... checks) {
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSignOffBundleConsistencyCheck> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(checks));
        return new FeatureSignOffBundleServiceImpl(write, transitions, registrar, actorPolicy, catalog, signOffMapper,
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
