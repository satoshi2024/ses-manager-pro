package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FE-C05（feature-enablement-console design §14。J-17）: 管理者の継続性 guard が、NF07 の開放状態と未決の申請を DB から読む。閉じていて
 * 未決の申請も無ければ privacy-admin の人数を判定せず（{@code INACTIVE}）、開いている・未決の申請があれば判定し（{@code ACTIVE}）、
 * 表を読めなければ guard は全て拒否する（{@code UNREADABLE}）。NF07 を DB で管理していなければ従来どおり判定する。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class PrivacyEnablementStateReaderH2Test {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private FeatureEnablementStateMapper stateMapper;
    @Autowired
    private FeatureEnablementRequestMapper requestMapper;

    private FeatureEnablementH2Fixture fixture;
    private PrivacyEnablementStateReader reader;

    @BeforeEach
    void setUp() {
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        reader = new PrivacyEnablementStateReader(properties, catalog, stateMapper, requestMapper,
                FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT));
    }

    @Test
    void NF07が閉じていればINACTIVEで開いていればACTIVE() {
        assertThat(reader.read()).isEqualTo(PrivacyEnablementStateReader.Result.INACTIVE);

        fixture.openDirectly(Feature.NF04, fixture.admin(), fixture.admin());
        assertThat(reader.read()).as("NF07 以外の開放は数えない").isEqualTo(PrivacyEnablementStateReader.Result.INACTIVE);

        fixture.openDirectly(Feature.NF07_DSAR, fixture.admin(), fixture.admin());
        assertThat(reader.read()).isEqualTo(PrivacyEnablementStateReader.Result.ACTIVE);
    }

    @Test
    void 未決の申請があればACTIVE() {
        FeatureEnablementActor requester = fixture.admin();
        jdbc.update("INSERT INTO t_feature_enablement_request (tenant_id, request_type, target_key, status, "
                        + "condition_fingerprint, condition_summary, requested_by, requested_at, expires_at, version) VALUES "
                        + "(?, 'OPEN', 'NF07_RETENTION', 'PENDING', ?, 'fixture', ?, TIMESTAMP '2026-11-23 09:00:00', "
                        + "TIMESTAMP '2026-11-30 09:00:00', 0)", FeatureEnablementH2Fixture.TENANT, "e".repeat(64),
                requester.userId());

        assertThat(reader.read()).isEqualTo(PrivacyEnablementStateReader.Result.ACTIVE);
    }

    @Test
    void 表を読めなければUNREADABLEでDB管理でなければNOT_DATABASE_MANAGED() {
        FeatureEnablementStateMapper broken = mock(FeatureEnablementStateMapper.class);
        when(broken.selectByTenant(any())).thenThrow(new IllegalStateException("io"));
        assertThat(new PrivacyEnablementStateReader(properties, catalog, broken, requestMapper,
                FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT)).read())
                .isEqualTo(PrivacyEnablementStateReader.Result.UNREADABLE);

        FeatureEnablementCatalog propertyManaged = FeatureEnablementCatalog.standard()
                .withDatabaseManaged(FeatureEnablementCatalog.STAGE_A_DATABASE_MANAGED);
        assertThat(new PrivacyEnablementStateReader(properties, propertyManaged, stateMapper, requestMapper,
                FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT)).read())
                .isEqualTo(PrivacyEnablementStateReader.Result.NOT_DATABASE_MANAGED);
    }
}
