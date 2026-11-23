package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.featureenablement.state.DatabaseFeatureEnablementStateSource;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.PropertyFeatureEnablementStateSource;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A11（feature-enablement-console design §3.1・§8.1、FE-RQ-001・006）: 段階 A で DB 管理にした容易な7件を、本番の目録
 * （{@link FeatureEnablementCatalog#standard()} の印）と DB の源で開閉し、filter（遮断する入口）・menu・scheduler の入口判定・子の
 * gate（親が閉なら子も閉）・画面の表示制御の識別子が、同じ開閉を設定値で与えた property の源と同じ応答になることを確かめる。印の
 * 無い開放単位（NF01 二者確認・NF05・NF06・NF07）は DB の源でも property の値のまま。開閉の DB の行は試験の transaction の中で入れ、
 * rollback で消す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementEasyUnitsH2Test {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);
    private static final Set<Feature> STAGE_A = FeatureEnablementCatalog.STAGE_A_DATABASE_MANAGED;

    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureEnablementOverlay overlay;
    @Autowired
    private FeatureEnablementProperties properties;
    @Autowired
    private PropertyFeatureEnablementStateSource propertySource;
    @Autowired
    private FeatureSwitchProperties switchProperties;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor requester;
    private FeatureEnablementActor approver;
    private FeatureSwitches database;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        requester = fixture.admin();
        approver = fixture.admin();
        for (Feature feature : STAGE_A) {
            fixture.confirmedSignOffs(feature, SIGNED, EXPIRES);
        }
        // 本番の目録の段階 A の印（段階 C の NF07 の印は除く。NF07 は FE-C01〜C05 の試験が見る）で、試験の context の snapshot・
        // 一時の閉鎖・時計を読む DB の源
        database = new FeatureSwitches(new DatabaseFeatureEnablementStateSource(holder, overlay,
                FeatureEnablementCatalog.standard().withDatabaseManaged(STAGE_A), propertySource, properties, clock));
    }

    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        holder.reload();
    }

    @Test
    void 何も開いていなければ7件は閉じ印の無い開放単位はpropertyの値のまま() {
        holder.reload();

        assertSameAsProperty(EnumSet.noneOf(Feature.class));
        for (Feature feature : STAGE_A) {
            assertThat(database.isEnabled(feature)).as(feature.name()).isFalse();
        }
        for (Feature feature : EnumSet.complementOf(EnumSet.copyOf(STAGE_A))) {
            assertThat(database.isEnabled(feature)).as("印の無い %s は property の値", feature.name())
                    .isEqualTo(switchProperties.toggle(feature).isEnabled()
                            && feature.parent().map(parent -> database.isEnabled(parent)).orElse(true));
        }
    }

    @Test
    void 親が閉なら開いた子も閉で親を開くと子も開きpropertyの源と同じ応答になる() {
        open(Feature.NF01);
        open(Feature.NF03);
        open(Feature.NF04);
        open(Feature.NF10);
        fixture.openDirectly(Feature.NF02_CUSTOMER_PORTAL, requester, approver);
        holder.reload();

        assertSameAsProperty(EnumSet.of(Feature.NF01, Feature.NF03, Feature.NF04, Feature.NF10,
                Feature.NF02_CUSTOMER_PORTAL));
        assertThat(database.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).as("親 NF02 が閉なら子も閉").isFalse();
        assertThat(database.isEnabled(Feature.NF03_CERTIFICATION_PII)).isFalse();

        fixture.openDirectly(Feature.NF02, requester, approver);
        fixture.openDirectly(Feature.NF03_CERTIFICATION_PII, requester, approver);
        holder.reload();

        assertSameAsProperty(EnumSet.copyOf(STAGE_A));
        for (Feature feature : STAGE_A) {
            assertThat(database.isEnabled(feature)).as(feature.name()).isTrue();
        }
    }

    @Test
    void 親を閉じると子も自動閉鎖しpropertyの源で両方を閉じた時と同じ応答になる() {
        fixture.openDirectly(Feature.NF02, requester, approver);
        fixture.openDirectly(Feature.NF02_CUSTOMER_PORTAL, requester, approver);
        fixture.openDirectly(Feature.NF03, requester, approver);
        fixture.openDirectly(Feature.NF03_CERTIFICATION_PII, requester, approver);
        holder.reload();
        assertThat(database.isEnabled(Feature.NF02_CUSTOMER_PORTAL)).isTrue();

        service.close(Feature.NF02, "MANUAL_VERIFICATION", requester);
        service.close(Feature.NF03, "MANUAL_VERIFICATION", requester);
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();

        assertThat(fixture.state(Feature.NF02_CUSTOMER_PORTAL)).isEqualTo("AUTO_CLOSED");
        assertThat(fixture.closedReason(Feature.NF02_CUSTOMER_PORTAL)).isEqualTo("PARENT_CLOSED");
        assertThat(fixture.state(Feature.NF03_CERTIFICATION_PII)).isEqualTo("AUTO_CLOSED");
        assertSameAsProperty(EnumSet.noneOf(Feature.class));
    }

    /**
     * DB の源（本番の目録の印）の応答が、段階 A の7件を {@code opened} だけ開いた設定値（他の開放単位は試験の context の設定値）の
     * property の源と同じであることを、実効値・遮断する入口・menu・scheduler の入口判定・表示制御の識別子で比べる。
     */
    private void assertSameAsProperty(Set<Feature> opened) {
        FeatureSwitchProperties expectedProperties = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            expectedProperties.toggle(feature).setEnabled(STAGE_A.contains(feature) ? opened.contains(feature)
                    : switchProperties.toggle(feature).isEnabled());
        }
        FeatureSwitches expected = new FeatureSwitches(expectedProperties);
        List<String> menuKeys = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            assertThat(database.isEnabled(feature)).as("実効値 %s", feature.name()).isEqualTo(expected.isEnabled(feature));
            assertThat(FeatureSwitches.allows(database, feature)).as("scheduler の入口 %s", feature.name())
                    .isEqualTo(FeatureSwitches.allows(expected, feature));
            List<String> paths = new ArrayList<>(feature.pagePrefixes());
            paths.addAll(feature.apiPrefixes());
            paths.addAll(feature.exactPaths());
            for (String path : paths) {
                for (String candidate : List.of(path, path + "/x")) {
                    assertThat(database.blockingFeature(candidate)).as("filter %s", candidate)
                            .isEqualTo(expected.blockingFeature(candidate));
                }
            }
            menuKeys.addAll(feature.menuKeys());
        }
        menuKeys.add("dashboard");
        assertThat(database.visibleMenuKeys(menuKeys)).as("menu").isEqualTo(expected.visibleMenuKeys(menuKeys));
        assertThat(database.disabledKeys()).as("画面の表示制御").isEqualTo(expected.disabledKeys());
    }

    private void open(Feature feature) {
        com.ses.entity.FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).as(feature.name()).isTrue();
        FeatureEnablementH2Fixture.runAfterCommit();
        holder.reload();
    }
}
