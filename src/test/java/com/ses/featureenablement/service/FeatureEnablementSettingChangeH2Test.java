package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.service.servicedesk.PortalServiceRequestVisibilityPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-B03（feature-enablement-console design §5.6・§8.4、FE-RQ-010・FE-AC-010-01/02）: NF02 の公開範囲（visible-since）の変更は、
 * 設定の変更の申請（{@code SETTING_CHANGE}）と申請者と別の管理者の承認で行う。
 *
 * <ul>
 *   <li>申請者本人の承認は 403、別の管理者の承認で設定の行（申請者・承認者は申請の行と一致）を書き、次の判定から新しい範囲になる。
 *       未設定へ戻す変更も同じ。</li>
 *   <li>決定の署名の値と違う値は 409、offset の無い値は 400、今の値と同じ値は 409、値を設定する時に棚卸しの署名が無ければ 409。</li>
 *   <li>申請の後に決定の署名が差し替わると承認は STALE、条件が外れれば承認は 409 のまま申請は未決。</li>
 * </ul>
 * 行は試験の transaction の中で入れ、rollback で消す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementSettingChangeH2Test {

    private static final String KEY = FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE;
    private static final String V1 = "2027-04-01T00:00:00+09:00";
    private static final String V2 = "2027-05-01T00:00:00+09:00";
    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private FeatureEnablementSettingChangeService settingChanges;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementStateSource stateSource;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private com.ses.config.feature.FeatureSwitches switches;

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

    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        holder.reload();
    }

    @Test
    void 申請者本人の承認は403で別の管理者の承認で次の判定から新しい範囲になり未設定へも戻せる() {
        long decision = decision(V1);
        inventory();
        holder.reload();
        PortalServiceRequestVisibilityPolicy policy = new PortalServiceRequestVisibilityPolicy(stateSource);
        assertThat(policy.visibleSince()).as("承認の前は未設定（portal 起票だけ）").isEmpty();

        FeatureEnablementRequest request = settingChanges.request(KEY, " " + V1 + " ", "公開範囲の設定", requester);
        assertThat(request.getRequestType()).isEqualTo("SETTING_CHANGE");
        assertThat(request.getProposedValue()).isEqualTo(V1);
        assertThat(request.getConditionSummary()).doesNotContain(V1);
        BusinessException self = catchThrowableOfType(() -> service.approve(request.getId(), 0, null, requester),
                BusinessException.class);
        assertThat(self.getCode()).isEqualTo(403);
        assertThat(self.getMessageKey()).isEqualTo("error.featureEnablement.selfDecision");

        FeatureEnablementApprovalResult approved = service.approve(request.getId(), 0, null, approver);
        assertThat(approved.approved()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("SELECT setting_value, request_id, requested_by, approved_by "
                + "FROM t_feature_enablement_setting WHERE tenant_id = 'default' AND setting_key = ?", KEY);
        assertThat(row.get("SETTING_VALUE")).isEqualTo(V1);
        assertThat(((Number) row.get("REQUEST_ID")).longValue()).isEqualTo(request.getId());
        assertThat(((Number) row.get("REQUESTED_BY")).longValue()).isEqualTo(requester.userId());
        assertThat(((Number) row.get("APPROVED_BY")).longValue()).isEqualTo(approver.userId());
        assertThat(fixture.countEvents("SETTING", "CHANGED")).isEqualTo(1);
        assertThat(fixture.countAudit("FEATURE_SETTING_CHANGED")).isEqualTo(1);
        holder.reload();
        assertThat(policy.visibleSince()).as("承認の後の次の判定から新しい範囲").contains(OffsetDateTime.parse(V1));
        assertThat(settingChanges.settings(approver)).singleElement()
                .satisfies(view -> {
                    assertThat(view.value()).isEqualTo(V1);
                    assertThat(view.decisionValue()).isEqualTo(V1);
                    assertThat(view.pendingRequestId()).isNull();
                });

        // 未設定へ戻す（決定の署名の値も未設定にした決定）
        jdbc.update("UPDATE t_feature_sign_off SET structured_values = ? WHERE id = ?", "{\"visibleSince\":\"\"}", decision);
        holder.reload();
        FeatureEnablementRequest revert = settingChanges.request(KEY, "", null, requester);
        assertThat(revert.getProposedValue()).isNull();
        assertThat(service.approve(revert.getId(), 0, null, approver).approved()).isTrue();
        assertThat(jdbc.queryForObject("SELECT setting_value FROM t_feature_enablement_setting WHERE tenant_id = 'default' "
                + "AND setting_key = ?", String.class, KEY)).isNull();
        holder.reload();
        assertThat(policy.visibleSince()).isEmpty();
    }

    @Test
    void 署名の値と違う値は409でoffsetの無い値は400で同じ値と棚卸しの無い設定も409() {
        decision(V1);
        holder.reload();

        assertRejected(() -> settingChanges.request(KEY, V2, null, requester), 409,
                "error.featureEnablement.conditionsUnsatisfied", "setting.visibleSince.decision.valueMismatch");
        assertRejected(() -> settingChanges.request(KEY, "2027-04-01T00:00:00", null, requester), 400,
                "error.featureEnablement.settingValueInvalid", null);
        assertRejected(() -> settingChanges.request(KEY, V1, null, requester), 409,
                "error.featureEnablement.conditionsUnsatisfied", "setting.visibleSince.inventory.missing");
        assertRejected(() -> settingChanges.request(KEY, "", null, requester), 409,
                "error.featureEnablement.settingUnchanged", null);
        assertRejected(() -> settingChanges.request("nf02.other", V1, null, requester), 400,
                "error.featureEnablement.settingUnknown", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_request WHERE tenant_id = 'default' "
                + "AND request_type = 'SETTING_CHANGE'", Long.class)).isZero();
    }

    @Test
    void 決定の署名が無ければ未設定へ戻す申請もできない() {
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, proposed_value, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, decided_by, "
                        + "decided_at, version) VALUES ('default', 'SETTING_CHANGE', ?, ?, 'APPROVED', ?, 'fixture', ?, "
                        + "TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', ?, TIMESTAMP '2026-11-20 10:00:00', 1)",
                KEY, V1, "f".repeat(64), requester.userId(), approver.userId());
        Long requestId = jdbc.queryForObject("SELECT MAX(id) FROM t_feature_enablement_request WHERE target_key = ?",
                Long.class, KEY);
        jdbc.update("INSERT INTO t_feature_enablement_setting (tenant_id, setting_key, setting_value, version, request_id, "
                        + "requested_by, approved_by, approved_at) VALUES ('default', ?, ?, 0, ?, ?, ?, TIMESTAMP '2026-11-20 10:00:00')",
                KEY, V1, requestId, requester.userId(), approver.userId());
        holder.reload();
        assertRejected(() -> settingChanges.request(KEY, null, null, requester), 409,
                "error.featureEnablement.conditionsUnsatisfied", "setting.visibleSince.decision.missing");
    }

    @Test
    void 申請の後に決定の署名が差し替わると承認はSTALEで条件が外れれば承認は409のまま未決() {
        long decision = decision(V1);
        long inventory = inventory();
        holder.reload();
        FeatureEnablementRequest first = settingChanges.request(KEY, V1, null, requester);
        jdbc.update("UPDATE t_feature_sign_off SET version = version + 1 WHERE id = ?", decision);
        FeatureEnablementApprovalResult stale = service.approve(first.getId(), 0, null, approver);
        assertThat(stale.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.STALE);
        assertThat(fixture.requestStatus(first.getId())).isEqualTo("STALE");

        holder.reload();
        FeatureEnablementRequest second = settingChanges.request(KEY, V1, null, requester);
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE id = ?", LocalDate.of(2026, 11, 23), inventory);
        FeatureEnablementApprovalResult unsatisfied = service.approve(second.getId(), 0, null, approver);
        assertThat(unsatisfied.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED);
        assertThat(unsatisfied.codes()).containsExactly("setting.visibleSince.inventory.expiresOn.expired");
        assertThat(fixture.requestStatus(second.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_setting WHERE tenant_id = 'default'",
                Long.class)).isZero();
    }

    /**
     * 復審 rb-fe-b-656d71b8 の P3-1（統括の決定。D-4）: 公開範囲 V1 を承認して顧客ポータルを開いた後に、公開範囲の決定をより狭い決定
     * （V2。V1 より後）へ差し替えて確認すると、今の設定が決定の範囲の外になるため顧客ポータルだけが同じ transaction で
     * {@code AUTO_CLOSED} になり（親の NF02 は開いたまま）、監査と管理者への通知が残り、顧客へ見せる範囲は決定を超えない。開き直す
     * 申請も範囲の内を要し、設定を新しい決定の値へ変える（二人の承認）までは 409。承認の前に範囲は広がらない。
     */
    @Test
    void 公開範囲の決定をより狭い決定へ差し替えると顧客ポータルは自動閉鎖し開き直しは範囲の内を要する() {
        fixture.confirmedSignOffs(Feature.NF02, SIGNED, EXPIRES);
        Map<String, Long> portalSignOffs = fixture.confirmedSignOffs(Feature.NF02_CUSTOMER_PORTAL, SIGNED, EXPIRES);
        jdbc.update("UPDATE t_feature_sign_off SET structured_values = ? WHERE id = ?", "{\"visibleSince\":\"" + V1 + "\"}",
                portalSignOffs.get(FeatureEnablementSettingChangeApprover.KIND_DECISION));
        inventory();
        fixture.openDirectly(Feature.NF02, requester, approver);
        fixture.openDirectly(Feature.NF02_CUSTOMER_PORTAL, requester, approver);
        approvedSetting(V1);
        holder.reload();
        assertThat(switches.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();
        long autoClosedAudits = fixture.countAudit("FEATURE_AUTO_CLOSED");

        FeatureSignOff narrower = signOffs.register(new FeatureSignOffService.Registration(
                        FeatureEnablementSettingChangeApprover.KIND_DECISION, "CS-2026-1201", "CS_OWNER", null, SIGNED, EXPIRES,
                        Map.of("visibleSince", V2)), pdf(), "visible-since-decision.pdf", requester);
        signOffs.confirm(narrower.getId(), 0, approver);

        assertThat(fixture.state(Feature.NF02_CUSTOMER_PORTAL)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF02_CUSTOMER_PORTAL)).isEqualTo("SIGN_OFF_REPLACED_INVALID");
        assertThat(fixture.state(Feature.NF02)).as("親は開いたまま（その機能だけを閉じる）").isEqualTo("OPEN");
        assertThat(fixture.countAudit("FEATURE_AUTO_CLOSED")).isEqualTo(autoClosedAudits + 1);
        assertThat(FeatureEnablementH2Fixture.notices()).anyMatch(notice -> "autoClosed".equals(notice.eventKey())
                && Feature.NF02_CUSTOMER_PORTAL.name().equals(notice.args()[0]));
        holder.reload();
        assertThat(switches.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isFalse();

        assertRejected(() -> service.requestOpen(Feature.NF02_CUSTOMER_PORTAL, null, requester), 409,
                "error.featureEnablement.conditionsUnsatisfied", "setting.visibleSince.outsideDecision");

        FeatureEnablementRequest change = settingChanges.request(KEY, V2, null, requester);
        assertThat(service.approve(change.getId(), 0, null, approver).approved()).isTrue();
        holder.reload();
        FeatureEnablementRequest reopen = service.requestOpen(Feature.NF02_CUSTOMER_PORTAL, null, requester);
        assertThat(reopen.getStatus()).isEqualTo("PENDING");
        assertThat(reopen.getConditionSummary()).contains("setting.visibleSince.withinDecision=SATISFIED");
    }

    // ---------------------------------------------------------------- helper

    /** 承認済みの申請と設定の行を直接入れる（申請者・承認者は申請の行と一致。複合 FK）。 */
    private void approvedSetting(String value) {
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, proposed_value, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, decided_by, "
                        + "decided_at, version) VALUES ('default', 'SETTING_CHANGE', ?, ?, 'APPROVED', ?, 'fixture', ?, "
                        + "TIMESTAMP '2026-11-20 09:00:00', TIMESTAMP '2026-11-27 09:00:00', ?, TIMESTAMP '2026-11-20 10:00:00', 1)",
                KEY, value, "e".repeat(64), requester.userId(), approver.userId());
        Long requestId = jdbc.queryForObject("SELECT MAX(id) FROM t_feature_enablement_request WHERE target_key = ?",
                Long.class, KEY);
        jdbc.update("INSERT INTO t_feature_enablement_setting (tenant_id, setting_key, setting_value, version, request_id, "
                        + "requested_by, approved_by, approved_at) VALUES ('default', ?, ?, 0, ?, ?, ?, TIMESTAMP '2026-11-20 10:00:00')",
                KEY, value, requestId, requester.userId(), approver.userId());
    }

    private static byte[] pdf() {
        return ("%PDF-1.7\n1 0 obj<<>>endobj\n% visible-since\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private long decision(String visibleSince) {
        long id = fixture.signOff(FeatureEnablementSettingChangeApprover.KIND_DECISION, "CONFIRMED", SIGNED, EXPIRES);
        jdbc.update("UPDATE t_feature_sign_off SET structured_values = ? WHERE id = ?",
                "{\"visibleSince\":\"" + visibleSince + "\"}", id);
        return id;
    }

    private long inventory() {
        return fixture.signOff(FeatureEnablementSettingChangeApprover.KIND_INVENTORY, "CONFIRMED", SIGNED, EXPIRES);
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int code, String key,
                                       String argument) {
        BusinessException rejected = catchThrowableOfType(call, BusinessException.class);
        assertThat(rejected).as(key).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(code);
        assertThat(rejected.getMessageKey()).isEqualTo(key);
        if (argument != null) {
            assertThat(String.valueOf(List.of(rejected.getArgs()))).contains(argument);
        }
    }
}
