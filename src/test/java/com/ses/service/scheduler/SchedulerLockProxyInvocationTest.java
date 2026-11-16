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
            "privacyDsarDeadlineDaily", "certificationExpiryNotificationDaily", "serviceRequestAttachmentCompensation"};

    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    static Stream<Arguments> schedulers() {
        return Stream.of(
                Arguments.of(PrivacyDsarDeadlineScheduler.class, "notifyDaily", LOCK_NAMES[0]),
                Arguments.of(CertificationExpiryNotificationScheduler.class, "dispatchToday", LOCK_NAMES[1]),
                Arguments.of(ServiceRequestAttachmentCompensationScheduler.class, "retryDue", LOCK_NAMES[2]));
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

    @AfterTransaction
    void removeLockRows() {
        jdbcTemplate.update("DELETE FROM shedlock WHERE name IN (?, ?, ?)", (Object[]) LOCK_NAMES);
    }
}
