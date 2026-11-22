package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-A06（design §6、FE-RQ-004・017）: 1件の署名の登録・確認・却下・取下げ・取消し・差替え・取得を H2 で確かめる（組は
 * {@code FeatureSignOffBundleH2Test}、延長は {@code FeatureSignOffExtensionH2Test}）。試験の transaction の中で入れた行だけを読む。
 * 登録した文書の実体は試験の transaction の rollback で消える（登録の rollback の後始末）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureSignOffServiceH2Test {

    static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);
    private static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private java.util.List<com.ses.featureenablement.condition.FeatureConditionEvaluator> evaluators;
    @Autowired
    private FeatureEnablementWriteSupport write;
    @Autowired
    private FeatureSignOffRegistrar registrar;
    @Autowired
    private FeatureEnablementActorPolicy actorPolicy;
    @Autowired
    private com.ses.mapper.FeatureSignOffMapper signOffMapper;
    @Autowired
    private com.ses.mapper.FeatureEnablementRequestMapper requestMapper;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor registrant;
    private FeatureEnablementActor confirmer;
    private FeatureEnablementActor third;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        registrant = fixture.admin();
        confirmer = fixture.admin();
        third = fixture.admin();
    }

    @Test
    void 登録は文書を保存し確認は登録者以外で一覧に氏名を出さず取得は専用の経路でhashを照合する() {
        byte[] pdf = pdf("business-owner");
        FeatureSignOff registered = signOffs.register(registration("NF04_BUSINESS_OWNER", "BO-2026-1101",
                "BUSINESS_OWNER", SIGNED, EXPIRES), pdf, "nf04-owner.pdf", registrant);

        Map<String, Object> row = jdbc.queryForMap("SELECT status, signer_name, original_name, stored_name, content_sha256, "
                + "size_bytes, kind_class FROM t_feature_sign_off WHERE id = ?", registered.getId());
        assertThat(row).containsEntry("STATUS", "REGISTERED").containsEntry("SIGNER_NAME", "署名 太郎")
                .containsEntry("ORIGINAL_NAME", "nf04-owner.pdf").containsEntry("KIND_CLASS", "DECISION")
                .containsEntry("CONTENT_SHA256", FeatureSignOffRegistrar.sha256(pdf))
                .containsEntry("SIZE_BYTES", (long) pdf.length);
        assertThat(row.get("STORED_NAME")).isNotNull();
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_REGISTERED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT after_state FROM t_audit_log WHERE application_code = "
                + "'FEATURE_SIGN_OFF_REGISTERED' ORDER BY id DESC LIMIT 1", String.class)).doesNotContain("署名 太郎");

        assertForbidden(() -> signOffs.confirm(registered.getId(), 0, registrant),
                "error.featureEnablement.signOffSelfConfirm");
        FeatureSignOff confirmed = signOffs.confirm(registered.getId(), 0, confirmer);
        assertThat(confirmed.getStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.getConfirmedBy()).isEqualTo(confirmer.userId());
        assertThat(confirmed.getVersion()).isEqualTo(1);

        // 一覧の行は氏名と元の file 名を持たない（詳細だけが返す）
        assertThat(Arrays.stream(FeatureSignOffService.Summary.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("signerName", "originalName");
        assertThat(signOffs.list(third)).anyMatch(summary -> summary.id() == registered.getId()
                && "CONFIRMED".equals(summary.status()));
        assertThat(signOffs.detail(registered.getId(), third).getSignerName()).isEqualTo("署名 太郎");

        // 専用の経路は hash を照合して返し、取得を監査する。汎用の経路は種別で 403
        FeatureSignOffService.Document document = signOffs.document(registered.getId(), third);
        assertThat(document.content()).isEqualTo(pdf);
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_DOCUMENT_READ")).isEqualTo(1);
        String stored = (String) row.get("STORED_NAME");
        BusinessException generic = catchThrowableOfType(() -> AccountingTenantContextHolder.runWithTenant(
                FeatureEnablementH2Fixture.TENANT, () -> fileStorage.load(stored)), BusinessException.class);
        assertThat(generic.getCode()).isEqualTo(403);

        jdbc.update("UPDATE t_feature_sign_off SET content_sha256 = ? WHERE id = ?", "b".repeat(64), registered.getId());
        assertConflict(() -> signOffs.document(registered.getId(), third),
                "error.featureEnablement.signOffDocumentMismatch");
    }

    @Test
    void scanで拒否した文書は保存せず署名の行も作らない() {
        long rows = countSignOffs("NF04_SRE");
        long files = jdbc.queryForObject("SELECT COUNT(*) FROM t_file_security_metadata WHERE file_kind = "
                + "'FEATURE_SIGN_OFF' AND deleted_flag = 0", Long.class);

        BusinessException rejected = catchThrowableOfType(() -> signOffs.register(registration("NF04_SRE", "SRE-2026-11",
                "SRE", SIGNED, EXPIRES), pdf(EICAR), "infected.pdf", registrant), BusinessException.class);

        assertThat(rejected.getMessageKey()).isEqualTo("error.file.scanRejected");
        assertThat(countSignOffs("NF04_SRE")).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_file_security_metadata WHERE file_kind = "
                + "'FEATURE_SIGN_OFF' AND deleted_flag = 0", Long.class)).isEqualTo(files);
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_REGISTERED")).isZero();
    }

    @Test
    void 署名日が未来の確認は400で旧い署名は確認済みのまま() {
        long old = fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, EXPIRES);
        FeatureSignOff future = signOffs.register(registration("NF04_SRE", "SRE-2026-11", "SRE",
                LocalDate.of(2026, 11, 30), LocalDate.of(2027, 11, 30)), pdf("future"), "sre.pdf", registrant);

        BusinessException notYet = catchThrowableOfType(() -> signOffs.confirm(future.getId(), 0, confirmer),
                BusinessException.class);

        assertThat(notYet.getCode()).isEqualTo(400);
        assertThat(notYet.getMessageKey()).isEqualTo("error.featureEnablement.signOffNotYetSigned");
        assertThat(status(old)).isEqualTo("CONFIRMED");
        assertThat(status(future.getId())).isEqualTo("REGISTERED");

        // 署名日の当日になれば確認でき、旧い署名を先に SUPERSEDED にして差し替える
        clock.set(java.time.Instant.parse("2026-11-29T15:00:00Z"));
        signOffs.confirm(future.getId(), 0, confirmer);
        assertThat(status(old)).isEqualTo("SUPERSEDED");
        assertThat(status(future.getId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT superseded_by_id FROM t_feature_sign_off WHERE id = ?", Long.class, old))
                .isEqualTo(future.getId());
    }

    @Test
    void 差替えで成り立てば開いたまま成り立たなければ同じtransactionで自動閉鎖し失効した署名では差し替えない() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        open(Feature.NF04);
        long old = fixture.confirmedId("NF04_SRE");

        FeatureSignOff valid = signOffs.register(registration("NF04_SRE", "SRE-2026-1120", "SRE",
                LocalDate.of(2026, 11, 20), LocalDate.of(2027, 11, 20)), pdf("valid"), "sre.pdf", registrant);
        signOffs.confirm(valid.getId(), 0, confirmer);

        assertThat(status(old)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT purge_after FROM t_feature_sign_off WHERE id = ?", LocalDate.class, old))
                .isEqualTo(LocalDate.of(2029, 11, 23));
        assertThat(fixture.state(Feature.NF04)).as("成り立つ差替えは開いたまま").isEqualTo("OPEN");

        // 失効日が今日（当日から無効）の署名は登録できない（review rb-fe-a1 P3-2。確認済みの署名を失効した署名で差し替えない）
        BusinessException expired = catchThrowableOfType(() -> signOffs.register(registration("NF04_SRE", "SRE-2026-1101",
                "SRE", SIGNED, LocalDate.of(2026, 11, 23)), pdf("expired"), "sre.pdf", registrant), BusinessException.class);
        assertThat(expired.getCode()).isEqualTo(400);
        assertThat(expired.getMessageKey()).isEqualTo("error.featureEnablement.signOffAlreadyExpired");

        // 差し替えた後に継続の条件が成り立たなければ同じ transaction で自動閉鎖する（固有の条件は試験の評価器で作る）
        FeatureSignOff invalid = signOffs.register(registration("NF04_SRE", "SRE-INVALID-01", "SRE", SIGNED,
                LocalDate.of(2027, 11, 20)), pdf("invalid"), "sre.pdf", registrant);
        withReferenceCheck().confirm(invalid.getId(), 0, confirmer);

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_REPLACED_INVALID");
        assertThat(fixture.countAudit("FEATURE_AUTO_CLOSED")).isEqualTo(1);
    }

    @Test
    void schedulerが止まっていた間に失効した旧い署名の差替えでは先に自動閉鎖し開き直さない() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE' "
                + "AND status = 'CONFIRMED'", LocalDate.of(2026, 11, 25));
        open(Feature.NF04);
        clock.advance(Duration.ofDays(3));

        FeatureSignOff replacement = signOffs.register(registration("NF04_SRE", "SRE-2026-1124", "SRE",
                LocalDate.of(2026, 11, 24), LocalDate.of(2027, 11, 24)), pdf("late"), "sre.pdf", registrant);
        signOffs.confirm(replacement.getId(), 0, confirmer);

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).as("失効の先行の閉鎖").isEqualTo("SIGN_OFF_EXPIRED");
        assertThat(status(replacement.getId())).isEqualTo("CONFIRMED");
    }

    @Test
    void 取消しで依存する開放単位を自動閉鎖し全ての管理者へ通知する() {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        open(Feature.NF04);
        open(Feature.NF03);
        long privacy = fixture.confirmedId("NF04_PRIVACY");

        BusinessException noReason = catchThrowableOfType(() -> signOffs.revoke(privacy, 0, "取り下げ", third),
                BusinessException.class);
        assertThat(noReason.getCode()).isEqualTo(400);
        signOffs.revoke(privacy, 0, "SIGNER_WITHDREW", third);

        assertThat(status(privacy)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForMap("SELECT ended_by, end_reason, purge_after FROM t_feature_sign_off WHERE id = ?",
                privacy)).containsEntry("ENDED_BY", third.userId()).containsEntry("END_REASON", "SIGNER_WITHDREW");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_REVOKED");
        assertThat(fixture.state(Feature.NF03)).as("その種別を要しない開放単位は変えない").isEqualTo("OPEN");
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.notices()).as("取消しは全ての管理者へ")
                .filteredOn(notice -> notice.eventKey().equals("signOffRevoked")
                        && notice.recipients().contains(registrant.userId()))
                .singleElement().satisfies(notice -> assertThat(notice.args()).containsExactly("NF04_PRIVACY"));
    }

    @Test
    void 却下は登録者以外で取下げは登録者だけで確認済みは種別ごとに1件だけ() {
        FeatureSignOff first = signOffs.register(registration("NF04_SRE", "SRE-2026-11", "SRE", SIGNED, EXPIRES),
                pdf("first"), "sre.pdf", registrant);
        assertForbidden(() -> signOffs.reject(first.getId(), 0, "CONTENT_MISMATCH", registrant),
                "error.featureEnablement.signOffSelfConfirm");
        assertForbidden(() -> signOffs.withdraw(first.getId(), 0, confirmer),
                "error.featureEnablement.withdrawOnlyRegistrant");
        signOffs.withdraw(first.getId(), 0, registrant);
        assertThat(jdbc.queryForMap("SELECT status, end_reason FROM t_feature_sign_off WHERE id = ?", first.getId()))
                .containsEntry("STATUS", "REJECTED").containsEntry("END_REASON", "WITHDRAWN_BY_REGISTRANT");

        FeatureSignOff second = signOffs.register(registration("NF04_SRE", "SRE-2026-12", "SRE", SIGNED, EXPIRES),
                pdf("second"), "sre.pdf", registrant);
        signOffs.reject(second.getId(), 0, "CONTENT_MISMATCH", confirmer);
        assertThat(status(second.getId())).isEqualTo("REJECTED");
        assertConflict(() -> signOffs.confirm(second.getId(), 1, confirmer),
                "error.featureEnablement.signOffNotRegistered");

        // 確認済みは種別ごとに1件だけ（active_slot の一意索引）
        fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, EXPIRES);
        assertThatThrownBy(() -> fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, EXPIRES))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- helper

    /** 参照が {@code SRE-INVALID} で始まる NF04_SRE を継続の条件の不成立にする試験の評価器を足した部品の service。 */
    private FeatureSignOffService withReferenceCheck() {
        java.util.List<com.ses.featureenablement.condition.FeatureConditionEvaluator> all = new java.util.ArrayList<>(evaluators);
        all.add(new com.ses.featureenablement.condition.FeatureConditionEvaluator() {
            @Override
            public java.util.Set<Feature> features() {
                return java.util.EnumSet.of(Feature.NF04);
            }

            @Override
            public java.util.List<com.ses.featureenablement.condition.FeatureConditionCheck> evaluate(Feature feature,
                    com.ses.featureenablement.condition.FeatureEvaluationContext context) {
                var sre = context.snapshot().signOffs().get("NF04_SRE");
                boolean invalid = sre != null && sre.reference().startsWith("SRE-INVALID");
                return java.util.List.of(invalid
                        ? com.ses.featureenablement.condition.FeatureConditionCheck.unsatisfied("test.sre.reference",
                                com.ses.featureenablement.condition.FeatureConditionCheck.Category.AUTO, false)
                        : com.ses.featureenablement.condition.FeatureConditionCheck.satisfied("test.sre.reference",
                                com.ses.featureenablement.condition.FeatureConditionCheck.Category.AUTO, null));
            }
        });
        com.ses.featureenablement.condition.FeatureConditionRegistry registry =
                new com.ses.featureenablement.condition.FeatureConditionRegistry(catalog, all);
        return new FeatureSignOffServiceImpl(write, new FeatureSignOffTransitions(write, registry, catalog, signOffMapper),
                registrar, actorPolicy, catalog, signOffMapper, requestMapper, fileStorage,
                com.ses.featureenablement.state.FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT));
    }

    private void open(Feature feature) {
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private long countSignOffs(String kind) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off WHERE tenant_id = 'default' AND kind_code = ?",
                Long.class, kind);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, id);
    }

    static FeatureSignOffService.Registration registration(String kind, String reference, String role, LocalDate signedOn,
                                                           LocalDate expiresOn) {
        return new FeatureSignOffService.Registration(kind, reference, role, "署名 太郎", signedOn, expiresOn, Map.of());
    }

    /** 合成の PDF（magic byte と末尾を持つ）。 */
    static byte[] pdf(String marker) {
        return ("%PDF-1.7\n1 0 obj<<>>endobj\n% " + marker + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException forbidden = catchThrowableOfType(call, BusinessException.class);
        assertThat(forbidden).as(messageKey).isNotNull();
        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(forbidden.getMessageKey()).isEqualTo(messageKey);
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException conflict = catchThrowableOfType(call, BusinessException.class);
        assertThat(conflict).as(messageKey).isNotNull();
        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict.getMessageKey()).isEqualTo(messageKey);
    }
}
