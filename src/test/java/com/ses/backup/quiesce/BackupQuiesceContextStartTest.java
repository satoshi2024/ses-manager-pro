package com.ses.backup.quiesce;

import com.ses.config.AsyncConfig;
import com.ses.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-003（D3-P2-1・D4-P3-2）: 静止応答を有効にした context が prod の profile と prod 以外の profile の両方で
 * 起動し、depends-on の循環が無く、{@code dataSource} の依存が {@link BackupQuiesceLifecycleAnchor} だけであること。
 * {@code taskScheduler} は scheduling が有効な時だけ在り、在れば {@code dataSource} に依存する（無い時は何も足さない）。
 *
 * <p>prod 以外（test profile・{@code app.backup-quiesce.enabled=true}）はアプリ全体の context で確かめる（業務の service・mapper・
 * {@code SqlSessionFactory} と静止応答の間に循環が無いこと）。信号表へ commit するため専用の H2 DB を使い、試験の後に context を
 * 閉じる。prod の profile は自動構成の最小の組合せ（H2・外部依存なし）で、scheduling の有効・無効と停止口を確かめる。
 */
@SpringBootTest(properties = {
        "app.backup-quiesce.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:bq_context_start;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL"})
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BackupQuiesceContextStartTest {

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private BackupQuiesceLifecycleAnchor anchor;

    @Autowired
    private DataSource dataSource;

    @Test
    void prod以外のprofileでアプリ全体のcontextが起動しdataSourceの依存はanchorだけ() throws Exception {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        assertThat(beanFactory.getBeanDefinition("dataSource").getDependsOn())
                .containsExactly(BackupQuiesceLifecycleAnchor.BEAN_NAME);
        assertThat(beanFactory.getBeanDefinition("taskExecutor").getDependsOn()).contains("dataSource");
        assertThat(beanFactory.containsBeanDefinition("taskScheduler")).as("test profile は scheduling が無効").isFalse();
        assertThat(beanFactory.getBeanDefinition(BackupQuiesceLifecycleAnchor.BEAN_NAME).getDependsOn()).isNull();
        // anchor が依存するのは DataSourceProperties・BackupQuiesceProperties・Environment だけ
        for (String dependency : beanFactory.getDependenciesForBean(BackupQuiesceLifecycleAnchor.BEAN_NAME)) {
            assertThat(dependency).as("anchor の依存").satisfiesAnyOf(
                    name -> assertThat(name).contains("DataSourceProperties"),
                    name -> assertThat(name).contains("BackupQuiesceProperties"),
                    name -> assertThat(name).isEqualTo("environment"));
        }
        assertThat(Arrays.asList(beanFactory.getDependenciesForBean("backupQuiesceResponder")))
                .doesNotContain("dataSource", "taskExecutor");

        assertThat(anchor.gate().mode()).isEqualTo(BackupWriteGate.Mode.ARMED);
        assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.OPEN);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long giveUp = System.nanoTime() + 20_000_000_000L;
        Map<String, Object> row;
        do {
            Thread.sleep(200);
            row = jdbc.queryForMap("SELECT status, gate_state, protocol_version, max_hold_full_ms, scheduling_enabled "
                    + "FROM sys_backup_quiesce_instance WHERE instance_id = ?", anchor.instanceId());
        } while (!"RUNNING".equals(row.get("STATUS")) && System.nanoTime() - giveUp < 0);
        assertThat(row.get("STATUS")).as("ApplicationReadyEvent の後の heartbeat で RUNNING").isEqualTo("RUNNING");
        assertThat(row.get("GATE_STATE")).isEqualTo("OPEN");
        assertThat(((Number) row.get("MAX_HOLD_FULL_MS")).longValue()).isEqualTo(3_600_000L);
        assertThat(((Number) row.get("SCHEDULING_ENABLED")).intValue()).isZero();
    }

    // ---------------------------------------------------------------- prod の profile（最小の自動構成）

    private static ApplicationContextRunner prodRunner(String... properties) {
        String url = "jdbc:h2:mem:bq_prod_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:sql/schema-backup-quiesce-h2.sql'";
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                        DataSourceAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class, SchedulingConfig.class, BackupQuiesceConfiguration.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .withPropertyValues(properties);
    }

    @ParameterizedTest(name = "prod・scheduling={0}")
    @CsvSource({"true", "false"})
    void prodのprofileでschedulingの有効と無効のどちらでもcontextが起動し依存が循環しない(boolean scheduling) {
        prodRunner("app.scheduling.enabled=" + scheduling, "app.backup-quiesce.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            assertThat(beanFactory.getBeanDefinition("dataSource").getDependsOn())
                    .containsExactly(BackupQuiesceLifecycleAnchor.BEAN_NAME);
            assertThat(beanFactory.getBeanDefinition("taskExecutor").getDependsOn()).containsExactly("dataSource");
            assertThat(beanFactory.containsBeanDefinition("taskScheduler")).isEqualTo(scheduling);
            if (scheduling) {
                assertThat(beanFactory.getBeanDefinition("taskScheduler").getDependsOn()).containsExactly("dataSource");
            }
            BackupQuiesceLifecycleAnchor anchor = context.getBean(BackupQuiesceLifecycleAnchor.class);
            assertThat(anchor.gate().isProdProfile()).isTrue();
            assertThat(anchor.gate().state()).as("登録の後、有効な要求が無ければ開く").isEqualTo(BackupWriteGate.State.OPEN);
            List<Map<String, Object>> rows = new JdbcTemplate(context.getBean(DataSource.class)).queryForList(
                    "SELECT gate_state, scheduling_enabled FROM sys_backup_quiesce_instance WHERE instance_id = ?",
                    anchor.instanceId());
            assertThat(rows).singleElement().satisfies(row -> {
                // 登録の時は UNARMED。次の heartbeat（5 秒後）からは OPEN
                assertThat(row.get("GATE_STATE")).isIn("UNARMED", "OPEN");
                assertThat(((Number) row.get("SCHEDULING_ENABLED")).intValue()).isEqualTo(scheduling ? 1 : 0);
            });
        });
    }

    @Test
    void prodの停止口はDISABLEDで登録しprod以外のenabled_falseは登録もpoolも作らない() {
        prodRunner("app.scheduling.enabled=false", "app.backup-quiesce.enabled=false",
                "app.backup-quiesce.disabled-ticket=CHG-1234").run(context -> {
            assertThat(context).hasNotFailed();
            BackupQuiesceLifecycleAnchor anchor = context.getBean(BackupQuiesceLifecycleAnchor.class);
            assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.DISABLED);
            assertThat(new JdbcTemplate(context.getBean(DataSource.class)).queryForObject(
                    "SELECT gate_state FROM sys_backup_quiesce_instance WHERE instance_id = ?", String.class,
                    anchor.instanceId())).isEqualTo("DISABLED");
        });

        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                        DataSourceAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class, BackupQuiesceConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:bq_inert;MODE=MySQL",
                        "app.backup-quiesce.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    BackupQuiesceLifecycleAnchor anchor = context.getBean(BackupQuiesceLifecycleAnchor.class);
                    assertThat(anchor.gate().mode()).isEqualTo(BackupWriteGate.Mode.INERT);
                    assertThat(anchor.isActive()).isFalse();
                    assertThat(anchor.pools()).as("接続 pool を作らない").isNull();
                    assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.OPEN);
                });
    }

    @Test
    void DataSourceもtaskSchedulerも無いcontextでは破棄の順の固定は何もしない() {
        new ApplicationContextRunner()
                .withBean(BackupQuiesceDestructionOrderPostProcessor.class)
                .withUserConfiguration(AsyncConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanFactory().getBeanDefinition("taskExecutor").getDependsOn()).isNull();
                });
    }
}
