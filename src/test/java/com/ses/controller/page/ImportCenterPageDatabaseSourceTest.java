package com.ses.controller.page;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * FE-B02（feature-enablement-console design §8.3）: DB の源（NF06 に DB 管理の印）で、NF06 の開放状態が閉なら取込センターの画面・API・
 * サイドバー導線は管理者にも出ず（{@link ImportCenterPageFeatureOffTest} の property の源と同じ 404）、開けば画面が出る
 * （{@link ImportCenterPageControllerTest} と同じ）。期間の外で NF06 を閉じると upload だけでなく NF06 全体が 404 になる（D-8・J-09）。
 * 開放状態の行は試験の transaction の中で入れ、rollback で消す。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class ImportCenterPageDatabaseSourceTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 10, 1);

    @Autowired
    private MockMvc mockMvc;
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

    private FeatureEnablementH2Fixture fixture;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
    }

    @AfterTransaction
    void restoreSharedState() {
        for (Feature feature : Feature.values()) {
            overlay.open(feature, FeatureEnablementOverlay.AUTO_CLOSE_PENDING);
        }
        holder.reload();
    }

    @Test
    @WithMockUser(username = "nf06-db-admin", roles = "管理者")
    void NF06が閉なら管理者でも画面とAPIが404で開けば画面が出て期間の外では全体が404になる() throws Exception {
        holder.reload();
        mockMvc.perform(get("/import-center")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/import-center/sources"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        assertThat(mockMvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).doesNotContain("href=\"/import-center\"");

        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        fixture.confirmedSignOffs(Feature.NF06, SIGNED, EXPIRES);
        fixture.openDirectly(Feature.NF06, requester, approver);
        holder.reload();
        mockMvc.perform(get("/import-center"))
                .andExpect(status().isOk())
                .andExpect(view().name("import-center/index"));

        // admission の署名の失効日の当日から、自動閉鎖の書込みの前でも実効値は閉じ、画面・API とも 404（upload だけの 403 ではない）
        jdbc.update("UPDATE t_feature_sign_off SET expires_on = ? WHERE tenant_id = 'default' AND kind_code = 'NF06_ADMISSION' "
                + "AND status = 'CONFIRMED'", LocalDate.of(2026, 11, 23));
        holder.reload();
        mockMvc.perform(get("/import-center")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/import-center/sources")).andExpect(status().isNotFound());
    }
}
