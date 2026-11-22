package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A04（design §3.2 の最初の読込み）: DB の源では snapshot の最初の読込みを holder の初期化（DB の初期化の後）で同期して行うため、
 * {@code FeatureSwitches.logStates}（{@code @PostConstruct}）は読込みの後の状態を見る。専用の H2 DB の初期化（{@code data-locations}）で
 * NF04 を開いた状態を入れ、起動の時点の実効値の log（{@code lastDescribed}）に {@code NF04=ON} が出ることを確かめる。
 */
@SpringBootTest(properties = {
        "app.feature-enablement.source=database",
        "spring.sql.init.data-locations=classpath:sql/feature-enablement-startup-seed.sql"})
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class FeatureEnablementSnapshotHolderStartupTest {

    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private FeatureSwitches switches;
    @Autowired
    private FeatureEnablementStateSource source;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "fe_startup_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @Test
    void 起動の時点の実効値のlogは最初の読込みの後の状態を見る() {
        assertThat(source).isInstanceOf(DatabaseFeatureEnablementStateSource.class);
        assertThat(holder.loaded()).as("初期化で読み込んだ").isTrue();
        assertThat(holder.get().generation()).isEqualTo(3L);
        assertThat(holder.get().unit(Feature.NF04).state()).isEqualTo(EnablementState.OPEN);

        assertThat(switches.isEnabled(Feature.NF04)).isTrue();
        assertThat(switches.isEnabled(Feature.NF01)).isFalse();
        assertThat((String) ReflectionTestUtils.getField(switches, "lastDescribed"))
                .as("logStates（@PostConstruct）は読込みの後の状態を出した")
                .contains("NF04=ON").contains("NF01=OFF");
    }
}
