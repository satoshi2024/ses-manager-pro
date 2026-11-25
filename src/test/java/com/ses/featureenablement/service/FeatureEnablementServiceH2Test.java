package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * FE-A05（design §5、FE-RQ-002・003・013）: 申請・承認・却下・取下げ・閉鎖・期限切れ・陳腐化・cascade・監査と履歴を H2 で確かめる。
 * 試験の transaction の中で入れた行だけを読み、rollback で消す（{@code TestIsolationAuditTest}）。commit の後の通知と snapshot の
 * 読直しは {@link FeatureEnablementH2Fixture#runAfterCommit()} で同じ transaction の中から呼ぶ。STALE が commit されてから 409 が
 * 返ることは実 MySQL の並行の試験（{@code FeatureEnablementConcurrencyMySqlTest}）が確かめる。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementServiceH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 11, 1);

    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureSwitches switches;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor requester;
    private FeatureEnablementActor approver;
    private FeatureEnablementActor third;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        requester = fixture.admin();
        approver = fixture.admin();
        third = fixture.admin();
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
    }

    @Test
    void 申請と別の管理者の承認で開き実効値はcommitの後の読直しで開き監査と履歴は1件ずつ() {
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, "利用者向けの文言の確認", requester);

        assertThat(fixture.requestStatus(request.getId())).isEqualTo("PENDING");
        assertThat(fixture.countEvents("REQUEST", "REQUESTED")).isEqualTo(1);
        assertThat(fixture.countAudit("FEATURE_OPEN_REQUESTED")).isEqualTo(1);
        assertThat(request.getConditionFingerprint()).hasSize(64);
        assertThat(request.getConditionSummary()).contains("signoff.NF04_SRE.valid=SATISFIED")
                .doesNotContain(requester.username());
        assertThat(request.getExpiresAt()).isEqualTo(request.getRequestedAt().plusDays(7));
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.countNotices(approver.userId(), "requested")).as("申請者以外の管理者へ承認の依頼")
                .isEqualTo(1);
        assertThat(FeatureEnablementH2Fixture.countNotices(requester.userId(), "requested")).as("申請者には送らない").isZero();

        FeatureEnablementApprovalResult result = service.approve(request.getId(), 0, null, approver);

        assertThat(result.approved()).isTrue();
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("APPROVED");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("OPEN");
        assertThat(jdbc.queryForMap("SELECT opened_by, approved_by, open_request_id FROM t_feature_enablement_state "
                + "WHERE tenant_id = 'default' AND feature_key = 'NF04'"))
                .containsEntry("OPENED_BY", requester.userId()).containsEntry("APPROVED_BY", approver.userId())
                .containsEntry("OPEN_REQUEST_ID", request.getId());
        assertThat(fixture.countEvents("FEATURE", "APPROVED")).isEqualTo(1);
        assertThat(fixture.countAudit("FEATURE_OPEN_APPROVED")).isEqualTo(1);
        assertThat(switches.isEnabled(Feature.NF04)).as("commit の前は差し替えない").isFalse();
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(switches.isEnabled(Feature.NF04)).as("commit の後の読直しで開く").isTrue();

        // 開いている間の申請は 409（許さない遷移）
        assertConflict(() -> service.requestOpen(Feature.NF04, null, third), "error.featureEnablement.stateNotRequestable");
    }

    @Test
    void 申請者による承認と却下は403で同じ対象の2件目の未決は409で許さない遷移は409() {
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, null, requester);

        assertForbidden(() -> service.approve(request.getId(), 0, null, requester), "error.featureEnablement.selfDecision");
        assertForbidden(() -> service.reject(request.getId(), 0, "不要", requester), "error.featureEnablement.selfDecision");
        assertConflict(() -> service.requestOpen(Feature.NF04, null, approver), "error.featureEnablement.pendingExists");
        assertConflict(() -> service.approve(request.getId(), 5, null, approver),
                "error.featureEnablement.requestVersionMismatch");
        assertConflict(() -> service.close(Feature.NF04, "MANUAL_OTHER", approver), "error.featureEnablement.stateNotOpen");
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("PENDING");
        assertThat(fixture.countEvents("FEATURE", "CLOSED")).isZero();

        // 管理者以外は申請も承認もできない
        FeatureEnablementActor sales = fixture.user("営業");
        BusinessException denied = catchThrowableOfType(() -> service.approve(request.getId(), 0, null, sales),
                BusinessException.class);
        assertThat(denied.getCode()).isEqualTo(403);
    }

    @Test
    void 却下は申請者以外で理由が必須で取下げは申請者だけ() {
        FeatureEnablementRequest first = service.requestOpen(Feature.NF04, null, requester);
        BusinessException noReason = catchThrowableOfType(() -> service.reject(first.getId(), 0, " ", approver),
                BusinessException.class);
        assertThat(noReason.getCode()).isEqualTo(400);
        service.reject(first.getId(), 0, "署名の内容を確かめ直す", approver);
        assertThat(fixture.requestStatus(first.getId())).isEqualTo("REJECTED");
        assertThat(fixture.countAudit("FEATURE_OPEN_REJECTED")).isEqualTo(1);
        assertThat(fixture.state(Feature.NF04)).as("開放状態は変えない").isEqualTo("CLOSED");

        FeatureEnablementRequest second = service.requestOpen(Feature.NF04, null, requester);
        assertForbidden(() -> service.withdraw(second.getId(), 0, approver),
                "error.featureEnablement.withdrawOnlyRequester");
        service.withdraw(second.getId(), 0, requester);
        assertThat(fixture.requestStatus(second.getId())).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT decided_by FROM t_feature_enablement_request WHERE id = ?", Long.class,
                second.getId())).isEqualTo(requester.userId());
    }

    @Test
    void 期限を過ぎた申請の承認はEXPIREDで終わり新しい申請の時に期限切れの未決を先に終える() {
        FeatureEnablementRequest stale = service.requestOpen(Feature.NF04, null, requester);
        clock.advance(Duration.ofDays(7).plusSeconds(1));

        FeatureEnablementApprovalResult expired = service.approve(stale.getId(), 0, null, approver);
        assertThat(expired.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.EXPIRED);
        assertThat(fixture.requestStatus(stale.getId())).isEqualTo("EXPIRED");

        FeatureEnablementRequest pending = service.requestOpen(Feature.NF04, null, requester);
        clock.advance(Duration.ofDays(8));
        FeatureEnablementRequest renewed = service.requestOpen(Feature.NF04, null, approver);
        assertThat(fixture.requestStatus(pending.getId())).as("申請の時の遅延の期限切れ").isEqualTo("EXPIRED");
        assertThat(fixture.requestStatus(renewed.getId())).isEqualTo("PENDING");
        assertThat(fixture.countAudit("FEATURE_OPEN_EXPIRED")).isEqualTo(2);
    }

    @Test
    void 申請の後に署名が差し替わればSTALEで終わり失効や取消しなら承認は409で未決のまま() {
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, null, requester);
        long old = fixture.confirmedId("NF04_SRE");
        jdbc.update("UPDATE t_feature_sign_off SET status = 'SUPERSEDED' WHERE id = ?", old);
        fixture.signOff("NF04_SRE", "CONFIRMED", SIGNED, EXPIRES);

        FeatureEnablementApprovalResult stale = service.approve(request.getId(), 0, null, approver);
        assertThat(stale.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.STALE);
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("STALE");
        assertThat(fixture.countAudit("FEATURE_OPEN_STALE")).isEqualTo(1);
        assertThat(fixture.state(Feature.NF04)).isEqualTo("CLOSED");

        FeatureEnablementRequest second = service.requestOpen(Feature.NF04, null, requester);
        jdbc.update("UPDATE t_feature_sign_off SET status = 'REVOKED', ended_by = 990003, ended_at = TIMESTAMP "
                + "'2026-11-23 10:00:00', end_reason = 'TEST' WHERE id = ?", fixture.confirmedId("NF04_PRIVACY"));
        FeatureEnablementApprovalResult unsatisfied = service.approve(second.getId(), 0, null, approver);
        assertThat(unsatisfied.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED);
        assertThat(unsatisfied.codes()).contains("signoff.NF04_PRIVACY.missing");
        assertThat(fixture.requestStatus(second.getId())).as("申請は未決のまま").isEqualTo("PENDING");
        assertThat(fixture.state(Feature.NF04)).isEqualTo("CLOSED");
    }

    @Test
    void 要る署名が無ければ申請は409で行は増えない() {
        BusinessException unsatisfied = catchThrowableOfType(() -> service.requestOpen(Feature.NF10, null, requester),
                BusinessException.class);
        assertThat(unsatisfied.getCode()).isEqualTo(409);
        assertThat(unsatisfied.getMessageKey()).isEqualTo("error.featureEnablement.conditionsUnsatisfied");
        assertThat(String.valueOf(unsatisfied.getArgs()[0])).contains("signoff.NF10_REPORT_OWNER.missing");
        // 固有の評価器を要する開放単位は評価器が無ければ申請できない（FE-AC-001-02）。段階 B で NF06、段階 D で NF05 に評価器を
        // 足したため（段階の整合 M4）、評価器が prod だけにある NF07 保持・保全（PrivacyProductionConfigurationValidator は
        // @Profile("prod")）で、test の profile の context で確かめる。NF07 の申請者は privacy-admin に限るため、所属させた管理者で申請する
        fixture.confirmedSignOffs(Feature.NF07_RETENTION, SIGNED, EXPIRES);
        FeatureEnablementActor privacyAdmin = privacyAdmin();
        BusinessException missing = catchThrowableOfType(() -> service.requestOpen(Feature.NF07_RETENTION, null,
                privacyAdmin), BusinessException.class);
        assertThat(missing.getCode()).isEqualTo(409);
        assertThat(String.valueOf(missing.getArgs()[0])).contains("evaluator.missing");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_request WHERE tenant_id = 'default'",
                Long.class)).isZero();
    }

    @Test
    void 変更のtransactionの最初に失効した開放単位を自動閉鎖する() {
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE tenant_id = 'default' AND kind_code = 'NF04_SRE'",
                LocalDate.of(2026, 11, 25));
        open(Feature.NF04);
        clock.advance(Duration.ofDays(3));

        service.expirePendingRequests();

        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF04)).isEqualTo("SIGN_OFF_EXPIRED");
        assertThat(fixture.countAudit("FEATURE_AUTO_CLOSED")).isEqualTo(1);
    }

    @Test
    void 親の閉鎖で子も自動閉鎖し未決を取り消し親を開き直しても子は閉じたまま() {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03_CERTIFICATION_PII, SIGNED, EXPIRES);
        open(Feature.NF03);
        open(Feature.NF03_CERTIFICATION_PII);
        FeatureEnablementRequest pendingNf04 = service.requestOpen(Feature.NF04, null, requester);

        service.close(Feature.NF03, "MANUAL_SECURITY_INCIDENT", third);

        assertThat(fixture.state(Feature.NF03)).isEqualTo("MANUALLY_CLOSED");
        assertThat(fixture.closedReason(Feature.NF03)).isEqualTo("MANUAL_SECURITY_INCIDENT");
        assertThat(fixture.state(Feature.NF03_CERTIFICATION_PII)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF03_CERTIFICATION_PII)).isEqualTo("PARENT_CLOSED");
        assertThat(fixture.requestStatus(pendingNf04.getId())).as("他の開放単位の未決は残す").isEqualTo("PENDING");
        FeatureEnablementH2Fixture.runAfterCommit();
        assertThat(FeatureEnablementH2Fixture.countNotices(requester.userId(), "closed")).as("手動の閉鎖は全ての管理者へ")
                .isEqualTo(1);

        open(Feature.NF03);
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
        assertThat(fixture.state(Feature.NF03_CERTIFICATION_PII)).as("子は開き直すまで閉じたまま").isEqualTo("AUTO_CLOSED");

        // 閉鎖の理由は code だけ（自由記述を書かない）
        BusinessException invalid = catchThrowableOfType(() -> service.close(Feature.NF03, "誤って開いた", third),
                BusinessException.class);
        assertThat(invalid.getCode()).isEqualTo(400);
        // 手動の閉鎖の理由は目録の区分だけ（system の理由の code を書かせない。review rb-fe-a1 P3-4）
        BusinessException systemReason = catchThrowableOfType(() -> service.close(Feature.NF03, "SIGN_OFF_REVOKED", third),
                BusinessException.class);
        assertThat(systemReason.getCode()).isEqualTo(400);
        assertThat(systemReason.getMessageKey()).isEqualTo("error.featureEnablement.reasonCodeInvalid");
    }

    @Test
    void 監査を書けなければ遷移のtransactionはrollbackになる() {
        doThrow(new IllegalStateException("audit")).when(auditLogService).recordDomainEventRequired(any());

        assertThat(catchThrowableOfType(() -> service.requestOpen(Feature.NF04, null, requester),
                IllegalStateException.class)).isNotNull();

        // 試験の transaction に参加した遷移の transaction が rollback-only にした（commit されない。実 commit は MySQL の試験）
        org.springframework.transaction.TransactionStatus status = transactionManager.getTransaction(
                new org.springframework.transaction.support.DefaultTransactionDefinition());
        assertThat(status.isRollbackOnly()).isTrue();
        transactionManager.commit(status);
    }

    @Test
    void 総スイッチが閉じていれば申請と承認は409() {
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, null, requester);
        properties.setAllowed(false);
        try {
            assertConflict(() -> service.approve(request.getId(), 0, null, approver),
                    "error.featureEnablement.masterClosed");
            assertConflict(() -> service.requestOpen(Feature.NF03, null, approver), "error.featureEnablement.masterClosed");
        } finally {
            properties.setAllowed(true);
        }
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("PENDING");
    }

    // ---------------------------------------------------------------- helper

    private void open(Feature feature) {
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), fixture.requestVersion(request.getId()), null, approver).approved())
                .isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException conflict = catchThrowableOfType(call, BusinessException.class);
        assertThat(conflict).as(messageKey).isNotNull();
        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict.getMessageKey()).isEqualTo(messageKey);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException forbidden = catchThrowableOfType(call, BusinessException.class);
        assertThat(forbidden).as(messageKey).isNotNull();
        assertThat(forbidden.getCode()).isEqualTo(403);
        assertThat(forbidden.getMessageKey()).isEqualTo(messageKey);
    }

    /** privacy-admin group に所属させた有効な管理者（NF07 の行為者。{@code FeatureSignOffBundleH2Test} と同じ形）。 */
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

    @SuppressWarnings("unused")
    private Map<String, Object> state(Feature feature) {
        return jdbc.queryForMap("SELECT * FROM t_feature_enablement_state WHERE tenant_id = 'default' AND feature_key = ?",
                feature.name());
    }
}
