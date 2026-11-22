package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.signoff.FeatureSignOffPurgeScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-A06（design §2.6.2・§6.5、review P2-N2、spec の micro review P3-M1）: 証拠の類の署名の延長。新しい行を作らず、延長の申請
 * （{@code SIGN_OFF_EXTENSION}）を申請者以外の管理者が承認すると同じ行の失効日を延ばし、追記専用の延長の記録に1行を足す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureSignOffExtensionH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);

    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureSignOffPurgeScheduler purgeScheduler;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor requester;
    private FeatureEnablementActor approver;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        requester = fixture.admin();
        approver = fixture.admin();
    }

    @Test
    void 延長の承認で同じ行の失効日が延び延長の記録が1行で申請者本人の承認は403() {
        long id = fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, LocalDate.of(2027, 11, 1));
        String storedBefore = storedName(id);
        LocalDate extended = LocalDate.of(2028, 11, 1);

        FeatureEnablementRequest request = signOffs.requestExtension(id, extended, "受理の再確認", requester);

        assertThat(request.getRequestType()).isEqualTo("SIGN_OFF_EXTENSION");
        assertThat(request.getTargetKey()).isEqualTo("signoff:" + id);
        assertThat(request.getProposedValue()).isEqualTo("2028-11-01");
        BusinessException self = catchThrowableOfType(() -> service.approve(request.getId(), 0, null, requester),
                BusinessException.class);
        assertThat(self.getCode()).isEqualTo(403);
        assertThat(self.getMessageKey()).isEqualTo("error.featureEnablement.selfDecision");
        BusinessException pending = catchThrowableOfType(() -> signOffs.requestExtension(id, extended, null, approver),
                BusinessException.class);
        assertThat(pending.getMessageKey()).as("同じ署名に未決の延長は1件だけ").isEqualTo("error.featureEnablement.pendingExists");

        FeatureEnablementApprovalResult result = service.approve(request.getId(), 0, null, approver);

        assertThat(result.approved()).isTrue();
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForMap("SELECT status, expires_on, signed_on, stored_name, version FROM t_feature_sign_off "
                + "WHERE id = ?", id)).containsEntry("STATUS", "CONFIRMED").containsEntry("STORED_NAME", storedBefore)
                .containsEntry("VERSION", 1);
        assertThat(jdbc.queryForObject("SELECT expires_on FROM t_feature_sign_off WHERE id = ?", LocalDate.class, id))
                .isEqualTo(extended);
        assertThat(jdbc.queryForList("SELECT old_expires_on, new_expires_on, requested_by, confirmed_by FROM "
                + "t_feature_sign_off_extension WHERE sign_off_id = ?", id)).hasSize(1).first()
                .satisfies(row -> assertThat(row).containsEntry("REQUESTED_BY", requester.userId())
                        .containsEntry("CONFIRMED_BY", approver.userId()));
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_EXTENDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off WHERE tenant_id = 'default' AND "
                + "kind_code = 'NF04_SRE'", Long.class)).as("新しい行を作らない").isEqualTo(1);
    }

    @Test
    void 決定の類は延長できず上限を超える日付と今の失効日より前の日付は400() {
        long decision = fixture.signOff("NF04_BUSINESS_OWNER", "CONFIRMED", SIGNED, LocalDate.of(2027, 10, 1));
        BusinessException notExtendable = catchThrowableOfType(() -> signOffs.requestExtension(decision,
                LocalDate.of(2028, 1, 1), null, requester), BusinessException.class);
        assertThat(notExtendable.getCode()).isEqualTo(409);
        assertThat(notExtendable.getMessageKey()).isEqualTo("error.featureEnablement.signOffNotExtendable");

        long evidence = fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, LocalDate.of(2027, 11, 1));
        LocalDate today = LocalDate.of(2026, 11, 23);
        assertBadRequest(() -> signOffs.requestExtension(evidence, today.plusDays(1096), null, requester));
        assertBadRequest(() -> signOffs.requestExtension(evidence, LocalDate.of(2027, 10, 1), null, requester));
        assertThat(signOffs.requestExtension(evidence, today.plusDays(1095), null, requester).getStatus())
                .isEqualTo("PENDING");

        long registered = fixture.signOff("NF04_PRIVACY", "REGISTERED", SIGNED, LocalDate.of(2027, 11, 1));
        BusinessException unconfirmed = catchThrowableOfType(() -> signOffs.requestExtension(registered,
                LocalDate.of(2028, 1, 1), null, requester), BusinessException.class);
        assertThat(unconfirmed.getMessageKey()).isEqualTo("error.featureEnablement.signOffNotExtendable");

        // 失効日（当日から無効）を迎えた確認済みの証拠は、日の始まりの EXPIRED の前でも延ばさない（review rb-fe-a1 P3-2）
        long lapsed = fixture.signOff("NF04_DEVICE_ACCEPTANCE", "CONFIRMED", SIGNED, LocalDate.of(2026, 11, 23));
        BusinessException expired = catchThrowableOfType(() -> signOffs.requestExtension(lapsed, LocalDate.of(2027, 6, 1),
                null, requester), BusinessException.class);
        assertThat(expired.getCode()).isEqualTo(409);
        assertThat(expired.getMessageKey()).isEqualTo("error.featureEnablement.signOffAlreadyExpired");
    }

    @Test
    void 申請の後に対象の署名が変わるか確認済みでなくなればSTALEで終わる() {
        long id = fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, LocalDate.of(2027, 11, 1));
        FeatureEnablementRequest changed = signOffs.requestExtension(id, LocalDate.of(2028, 11, 1), null, requester);
        jdbc.update("UPDATE t_feature_sign_off SET version = version + 1 WHERE id = ?", id);

        FeatureEnablementApprovalResult stale = service.approve(changed.getId(), 0, null, approver);

        assertThat(stale.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.STALE);
        assertThat(fixture.requestStatus(changed.getId())).isEqualTo("STALE");
        assertThat(jdbc.queryForObject("SELECT expires_on FROM t_feature_sign_off WHERE id = ?", LocalDate.class, id))
                .isEqualTo(LocalDate.of(2027, 11, 1));

        FeatureEnablementRequest revoked = signOffs.requestExtension(id, LocalDate.of(2028, 11, 1), null, requester);
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = ?, ended_at = TIMESTAMP "
                + "'2026-11-23 10:00:00', end_reason = 'TEST' WHERE id = ?", approver.userId(), id);
        assertThat(service.approve(revoked.getId(), 0, null, approver).outcome())
                .isEqualTo(FeatureEnablementApprovalResult.Outcome.STALE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_sign_off_extension WHERE sign_off_id = ?",
                Long.class, id)).isZero();
    }

    @Test
    void 延長の後は旧い期限から3年を過ぎても文書を取得できる() {
        FeatureSignOff signOff = signOffs.register(FeatureSignOffServiceH2Test.registration("NF04_SRE", "SRE-2026-1101",
                "SRE", SIGNED, LocalDate.of(2026, 12, 1)), FeatureSignOffServiceH2Test.pdf("extend"), "sre.pdf",
                requester);
        signOffs.confirm(signOff.getId(), 0, approver);
        FeatureEnablementRequest request = signOffs.requestExtension(signOff.getId(), LocalDate.of(2029, 11, 20), null,
                requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();

        // 旧い期限（2026-12-01）の3年後を過ぎた日。行の最後の状態（確認済み）と延長の後の失効日で判定するため消さない
        clock.set(Instant.parse("2029-12-02T01:00:00Z"));
        purgeScheduler.purgeDue();

        assertThat(jdbc.queryForObject("SELECT purge_after FROM t_feature_sign_off WHERE id = ?", LocalDate.class,
                signOff.getId())).isNull();
        assertThat(signOffs.document(signOff.getId(), approver).content())
                .isEqualTo(FeatureSignOffServiceH2Test.pdf("extend"));
    }

    private String storedName(long id) {
        return jdbc.queryForObject("SELECT stored_name FROM t_feature_sign_off WHERE id = ?", String.class, id);
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        BusinessException rejected = catchThrowableOfType(call, BusinessException.class);
        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(400);
        assertThat(rejected.getMessageKey()).isEqualTo("error.featureEnablement.signOffExtensionInvalid");
    }

    @SuppressWarnings("unused")
    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM t_feature_sign_off WHERE id = ?", id);
    }
}
