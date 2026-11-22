package com.ses.featureenablement.evaluation;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.service.FeatureSignOffService;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * FE-A08（feature-enablement-console design §10・§11.5、FE-RQ-007）: 緊急の総スイッチが閉なら、DB の状態が開いていても最初の要求から
 * 全ての開放単位を閉じ、起動時の評価が {@code AUTO_CLOSED(MASTER_SWITCH_CLOSED)} にして未決を取り消す。閉の間も申請・承認は 409 で、
 * 署名の登録・確認はできる。開に戻しても自動では開かない（J-11）。試験の transaction の中で入れた行だけを読み、rollback で消す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementMasterSwitchH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private FeatureEnablementStartupEvaluator startupEvaluator;
    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureSwitches switches;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private JdbcTemplate jdbc;
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

    @AfterTransaction
    void restoreSharedState() {
        properties.setAllowed(true);
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        overlay.clearAll(FeatureEnablementOverlay.EVALUATION_UNVERIFIABLE);
        holder.reload();
    }

    @Test
    void 総スイッチが閉なら全て閉じ申請は409で署名の登録と確認はでき開に戻しても閉じたまま() {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF10, SIGNED, EXPIRES);
        open(Feature.NF03);
        open(Feature.NF04);
        FeatureEnablementRequest pending = service.requestOpen(Feature.NF10, null, requester);
        assertThat(switches.isEnabled(Feature.NF04)).isTrue();

        properties.setAllowed(false);
        holder.reload();

        assertThat(switches.isEnabled(Feature.NF03)).as("DB の状態は開いていても最初の要求から閉").isFalse();
        assertThat(switches.isEnabled(Feature.NF04)).isFalse();
        assertThat(switches.closedReasons(Feature.NF04)).contains("master.closed");
        assertThat(fixture.state(Feature.NF04)).as("評価の前は DB の状態を変えない").isEqualTo("OPEN");

        startupEvaluator.evaluateAtStartup();

        assertThat(fixture.state(Feature.NF03)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF03)).isEqualTo(FeatureEnablementEvaluationService.MASTER_SWITCH_CLOSED);
        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.requestStatus(pending.getId())).isEqualTo("CANCELLED");
        assertConflict(() -> service.requestOpen(Feature.NF03, null, requester), "error.featureEnablement.masterClosed");
        assertConflict(() -> service.approve(pending.getId(), fixture.requestVersion(pending.getId()), null, approver),
                "error.featureEnablement.masterClosed");

        FeatureSignOff registered = signOffs.register(new FeatureSignOffService.Registration("NF04_SRE", "SRE-2026-1122",
                        "SRE", "署名 太郎", LocalDate.of(2026, 11, 22), LocalDate.of(2027, 11, 22), Map.of()),
                ("%PDF-1.7\n1 0 obj<<>>endobj\n% master\n%%EOF\n").getBytes(StandardCharsets.US_ASCII), "sre.pdf",
                requester);
        FeatureSignOff confirmed = signOffs.confirm(registered.getId(), registered.getVersion(), approver);
        assertThat(confirmed.getStatus()).as("閉の間も署名の登録・確認はできる").isEqualTo("CONFIRMED");
        FeatureEnablementH2Fixture.runAfterCommit();

        properties.setAllowed(true);
        holder.reload();

        assertThat(switches.isEnabled(Feature.NF03)).as("開に戻しても自動では開かない（J-11）").isFalse();
        assertThat(switches.isEnabled(Feature.NF04)).isFalse();
        assertThat(fixture.state(Feature.NF04)).isEqualTo("AUTO_CLOSED");
        assertThat(service.requestOpen(Feature.NF04, null, requester).getStatus())
                .as("開に戻した後は二人の承認で開き直す").isEqualTo("PENDING");
    }

    private void open(Feature feature) {
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messageKey) {
        BusinessException conflict = catchThrowableOfType(call, BusinessException.class);
        assertThat(conflict).as(messageKey).isNotNull();
        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict.getMessageKey()).isEqualTo(messageKey);
    }
}
