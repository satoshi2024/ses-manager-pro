package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A04（design §3.3、FE-AC-006-01）: 同じ instance の書込みは、commit の後に snapshot を読み直して差し替える
 * （{@code TransactionSynchronization.afterCommit}）。commit の前は差し替えず、rollback では読み直さない。{@code @Transactional} の
 * rollback の試験では afterCommit が動かないため、本 class は試験の transaction を持たず、専用の H2 DB を使い、入れた行を
 * {@code @AfterEach} で明示的に消す（{@code TestIsolationAuditTest}）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FeatureEnablementAfterCommitRefreshTest {

    private static final String TENANT = "default";

    @Autowired
    private FeatureEnablementSnapshotHolder holder;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String database = "fe_after_commit_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + database
                + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM t_feature_enablement_state WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_enablement_request WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM t_feature_enablement_generation WHERE tenant_id = ?", TENANT);
        holder.reload();
    }

    @Test
    void commitの後に読み直し前は差し替えずrollbackでは読み直さない() {
        holder.reload();
        long before = holder.get().generation();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            writeClosed(before + 1);
            holder.reloadAfterCommit(List.of(Feature.NF04));
            assertThat(holder.get().generation()).as("commit の前は差し替えない").isEqualTo(before);
        });
        assertThat(holder.get().generation()).as("commit の後に読み直した").isEqualTo(before + 1);
        assertThat(holder.get().unit(Feature.NF04).state()).isEqualTo(EnablementState.MANUALLY_CLOSED);

        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE t_feature_enablement_generation SET generation = generation + 1 WHERE tenant_id = ?", TENANT);
            holder.reloadAfterCommit(List.of(Feature.NF04));
            status.setRollbackOnly();
        });
        assertThat(holder.get().generation()).as("rollback では読み直さない").isEqualTo(before + 1);
    }

    @Test
    void transactionの外では直ちに読み直す() {
        holder.reload();
        long before = holder.get().generation();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> writeClosed(before + 1));
        assertThat(holder.get().generation()).isEqualTo(before);

        holder.reloadAfterCommit(List.of(Feature.NF04));

        assertThat(holder.get().generation()).isEqualTo(before + 1);
    }

    private void writeClosed(long generation) {
        jdbc.update("MERGE INTO t_feature_enablement_generation (tenant_id, generation, updated_at) KEY (tenant_id) "
                + "VALUES (?, ?, TIMESTAMP '2026-11-23 10:00:00')", TENANT, generation);
        jdbc.update("INSERT INTO t_feature_enablement_state (tenant_id, feature_key, state, version, closed_reason, closed_by, "
                + "closed_at, updated_at) VALUES (?, 'NF04', 'MANUALLY_CLOSED', 1, 'MANUAL', 990010, "
                + "TIMESTAMP '2026-11-23 10:00:00', TIMESTAMP '2026-11-23 10:00:00')", TENANT);
    }
}
