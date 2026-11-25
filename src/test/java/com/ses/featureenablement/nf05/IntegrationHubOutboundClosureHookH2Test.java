package com.ses.featureenablement.nf05;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.service.integrationhub.IntegrationHubDigest;
import com.ses.service.integrationhub.IntegrationHubOutboundStartupBarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-D04（feature-enablement-console design §9.3、review rb-fe-d P3-13）: NF05 outbound の手動の閉鎖と自動閉鎖は、閉鎖の hook で同じ
 * transaction の中に barrier の行を {@code v1|OFF|0|<指紋>} に書き直し、{@code NF05_OUTBOUND_OFF}（{@code reference_type='NF05'}）を
 * 監査する。他の開放単位の閉鎖では行を変えない。試験の transaction は rollback する。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class IntegrationHubOutboundClosureHookH2Test {

    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        // 要る署名が無いと書込みの transaction の先行の閉鎖（SIGN_OFF_MISSING）が先に閉じるため、確認済みの署名を入れる
        fixture.confirmedSignOffs(Feature.NF05_OUTBOUND, java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2027, 6, 1));
        fixture.confirmedSignOffs(Feature.NF04, java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2027, 6, 1));
        jdbc.update("DELETE FROM m_system_config WHERE config_key = ?", IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
        jdbc.update("INSERT INTO m_system_config (config_key, config_value) VALUES (?, ?)",
                IntegrationHubOutboundStartupBarrier.CONFIG_KEY, "v1|ON|0|" + IntegrationHubDigest.sha256Hex("default"));
    }

    @Test
    void 手動の閉鎖はbarrierの行をOFFに書き直しOFFを監査する() {
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        fixture.openDirectly(Feature.NF05_OUTBOUND, requester, approver);
        long before = offAudits();

        service.close(Feature.NF05_OUTBOUND, "MANUAL_SECURITY_INCIDENT", requester);

        assertThat(barrier()).isEqualTo("v1|OFF|0|" + IntegrationHubDigest.sha256Hex("default"));
        assertThat(offAudits()).isEqualTo(before + 1);
    }

    @Test
    void 自動閉鎖もbarrierの行をOFFにし他の開放単位の閉鎖では変えない() {
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        fixture.openDirectly(Feature.NF04, requester, approver);
        service.close(Feature.NF04, "MANUAL_VERIFICATION", requester);
        assertThat(barrier()).as("NF05 outbound 以外は行を変えない").startsWith("v1|ON|");

        fixture.openDirectly(Feature.NF05_OUTBOUND, requester, approver);
        assertThat(service.autoClose(Feature.NF05_OUTBOUND, "TEST_AUTO_CLOSE", false)).isTrue();
        assertThat(barrier()).startsWith("v1|OFF|0|");
        assertThat(fixture.state(Feature.NF05_OUTBOUND)).isEqualTo("AUTO_CLOSED");
    }

    private String barrier() {
        return jdbc.queryForObject("SELECT config_value FROM m_system_config WHERE config_key = ?", String.class,
                IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
    }

    private long offAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_audit_log WHERE reference_type = 'NF05' "
                + "AND application_code = 'NF05_OUTBOUND_OFF'", Long.class);
    }
}
