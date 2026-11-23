package com.ses.service.scheduler;

import com.ses.privacy.production.dsar.PrivacyDsarDeadlineScheduler;
import com.ses.service.certification.CertificationExpiryNotificationScheduler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 定期実行の入口が ShedLock の proxy を通っても呼べることを、実 context で確かめる（NF07-A2a review P1-1・P1-X）。
 *
 * <p>ShedLock の PROXY_METHOD は primitive を返す method を lock できず、呼出しごとに {@code LockingNotSupportedException} を
 * 投げる。scheduler を {@code new} する単体テストでは proxy を通らないため気付けなかった。ここでは bean が ShedLock の advisor を
 * 持つ proxy であること、proxy 経由の呼出しが例外を出さず lock を取ることを確かめる。業務処理を走らせないよう、対象の機能スイッチは
 * OFF にした context で呼ぶ（入口の判定で戻る）。取った lock の行は test の transaction の後で物理削除する（ShedLock は lock を
 * 独立した transaction で commit し得るため）。
 */
@SpringBootTest(properties = {
        "app.features.nf02.enabled=false",
        "app.features.nf03.enabled=false",
        "privacy.dsar.enabled=false"})
@ActiveProfiles("test")
@Transactional
class SchedulerLockProxyInvocationTest {

    private static final String[] LOCK_NAMES = {
            "privacyDsarDeadlineDaily", "certificationExpiryNotificationDaily", "serviceRequestAttachmentCompensation",
            "featureEnablementEvaluation", "featureEnablementDayStart", "featureSignOffPurge"};

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Stream<Arguments> schedulers() {
        return Stream.of(
                Arguments.of(PrivacyDsarDeadlineScheduler.class, "notifyDaily", LOCK_NAMES[0]),
                Arguments.of(CertificationExpiryNotificationScheduler.class, "dispatchToday", LOCK_NAMES[1]),
                Arguments.of(ServiceRequestAttachmentCompensationScheduler.class, "retryDue", LOCK_NAMES[2]),
                // FE-A06・A07: 機能の開放の定期の評価・日の始まり・署名の消去（test profile の源は properties のため何も書かない）
                Arguments.of(com.ses.featureenablement.evaluation.FeatureEnablementConditionScheduler.class, "evaluateAll",
                        LOCK_NAMES[3]),
                Arguments.of(com.ses.featureenablement.evaluation.FeatureEnablementConditionScheduler.class,
                        "evaluateAtBusinessDayStart", LOCK_NAMES[4]),
                Arguments.of(com.ses.featureenablement.signoff.FeatureSignOffPurgeScheduler.class, "purge", LOCK_NAMES[5]));
    }

    @ParameterizedTest
    @MethodSource("schedulers")
    void scheduler_beanはShedLockのproxyでありproxy経由の定期実行の入口は例外を出さずlockを取る(
            Class<?> type, String methodName, String lockName) throws Exception {
        Object bean = context.getBean(type);

        assertThat(AopUtils.isAopProxy(bean)).as("%s は proxy", type.getSimpleName()).isTrue();
        assertThat(Arrays.stream(((Advised) bean).getAdvisors()))
                .as("%s に ShedLock の advisor が掛かっている", type.getSimpleName())
                .anyMatch(advisor -> advisor.getClass().getName().startsWith("net.javacrumbs.shedlock."));
        Method method = type.getMethod(methodName);
        assertThat(method.getReturnType()).isEqualTo(void.class);

        assertThatCode(() -> method.invoke(bean)).as("%s#%s を proxy 経由で呼ぶ", type.getSimpleName(), methodName)
                .doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shedlock WHERE name = ?", Integer.class, lockName))
                .as("proxy 経由で lock %s を取った", lockName).isEqualTo(1);
    }

    /**
     * 呼出しの前にも lock の行を消し、「lock を取った」の判定が残っていた行で成り立たないようにする（delta review D-3）。
     * 後でも消し、共有 H2 に行を残さない。
     */
    @BeforeTransaction
    @AfterTransaction
    void removeLockRows() {
        jdbcTemplate.update("DELETE FROM shedlock WHERE name IN (" + String.join(", ", java.util.Collections.nCopies(
                LOCK_NAMES.length, "?")) + ")", (Object[]) LOCK_NAMES);
    }

    // ---------------------------------------------------------------- backup の書込み静止（RB-QUIESCE BWQ-006）

    /** 最小の context の定期実行の入口（ShedLock の proxy を通る）。 */
    static class QuiesceProbe {
        final java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();

        @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(name = "backup-quiesce-scheduler-probe",
                lockAtMostFor = "PT1M")
        public void run() {
            invocations.incrementAndGet();
        }

        /** proxy（CGLIB）の field ではなく対象の数を読む。 */
        public int count() {
            return invocations.get();
        }
    }

    /**
     * 静止中に発火した定期実行は ShedLock の表へ書かずに待ち、解除の後に実行される（design.md §6.6。BWQ-AC-002-04）。
     * test profile の gate は INERT で閉じられないため、ShedLock の設定と静止の gate だけの最小の context で確かめる。
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
    void 静止中はshedlockの行を書かずに待ち解除の後にmethodを実行する() {
        com.ses.backup.quiesce.BackupWriteGate gate = com.ses.backup.quiesce.BackupQuiesceTestSupport.openGate(false,
                () -> 0L, java.time.Clock.systemUTC());
        String url = "jdbc:h2:mem:bq_shedlock_" + java.util.UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:sql/schema-shedlock-h2.sql'";
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class))
                .withUserConfiguration(com.ses.config.SchedulerLockConfig.class)
                .withBean(com.ses.backup.quiesce.BackupWriteGate.class, () -> gate)
                .withBean(QuiesceProbe.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "app.scheduler.lock.use-db-time=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    QuiesceProbe probe = context.getBean(QuiesceProbe.class);
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                    com.ses.backup.quiesce.BackupQuiesceTestSupport.close(gate, 1, java.time.Duration.ofMinutes(5));

                    java.util.concurrent.CompletableFuture<Void> fired =
                            java.util.concurrent.CompletableFuture.runAsync(probe::run);
                    Thread.sleep(500);
                    assertThat(fired).as("静止中は開始を待つ").isNotDone();
                    assertThat(probe.count()).isZero();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock", Integer.class))
                            .as("待っている間は ShedLock の表へ書かない").isZero();

                    com.ses.backup.quiesce.BackupQuiesceTestSupport.reopen(gate, 1);
                    fired.get(10, java.util.concurrent.TimeUnit.SECONDS);

                    assertThat(probe.count()).as("解除の後に飛ばさず実行する").isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock WHERE name = ?", Integer.class,
                            "backup-quiesce-scheduler-probe")).isEqualTo(1);
                    assertThat(gate.permitCount()).as("unlock の後で permit を返す").isZero();
                });
    }
}
