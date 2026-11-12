package com.ses.service.scheduler;

import com.ses.service.servicedesk.ServiceRequestAttachmentCompensationService;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import net.javacrumbs.shedlock.support.LockException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本番と同じ {@code @EnableSchedulerLock}（method proxy モード）でスケジューラBeanを包み、
 * ロック経由で本体が実行されることを検証する。DB・Docker は不要（LockProvider はインメモリ）。
 *
 * <p>{@code ServiceRequestAttachmentCompensationScheduler.retryDue} は以前 {@code int} を返しており、
 * 5分ごとに {@code LockingNotSupportedException} で失敗して補償再試行が一度も実行されていなかった。
 */
class SchedulerLockMethodProxyTest {

    @Test
    void 添付補償再試行はShedLockのproxy経由でロックを取得して実行される() {
        ServiceRequestAttachmentCompensationService service = mock(ServiceRequestAttachmentCompensationService.class);
        when(service.retryDue(ServiceRequestAttachmentCompensationScheduler.BATCH_SIZE)).thenReturn(3);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LockConfig.class);
            context.registerBean(ServiceRequestAttachmentCompensationScheduler.class,
                    () -> new ServiceRequestAttachmentCompensationScheduler(service));
            context.refresh();

            ServiceRequestAttachmentCompensationScheduler scheduler =
                    context.getBean(ServiceRequestAttachmentCompensationScheduler.class);
            assertThat(AopUtils.isAopProxy(scheduler))
                    .as("ShedLock の method proxy が適用されていること（未適用だと本テストは無意味）")
                    .isTrue();

            scheduler.retryDue();

            verify(service).retryDue(ServiceRequestAttachmentCompensationScheduler.BATCH_SIZE);
            assertThat(context.getBean(RecordingLockProvider.class).lockedNames)
                    .containsExactly("serviceRequestAttachmentCompensation");
        }
    }

    @Test
    void 件数はロックなしメソッドで取得できる() {
        ServiceRequestAttachmentCompensationService service = mock(ServiceRequestAttachmentCompensationService.class);
        when(service.retryDue(ServiceRequestAttachmentCompensationScheduler.BATCH_SIZE)).thenReturn(2);

        assertThat(new ServiceRequestAttachmentCompensationScheduler(service).retryDueBatch()).isEqualTo(2);
    }

    /**
     * 前提の確認: 静的検査（{@link ScheduledMethodsHaveSchedulerLockTest}）がプリミティブ戻り値を禁止する根拠。
     * ShedLock のバージョンアップでプリミティブがロック可能になればこのテストが失敗し、規則の見直し時期が分かる。
     */
    @Test
    void ShedLockのmethod_proxyはプリミティブ戻り値のメソッドをロックできない() {
        AtomicInteger executions = new AtomicInteger();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LockConfig.class);
            context.registerBean(PrimitiveReturningJob.class, () -> new PrimitiveReturningJob(executions));
            context.refresh();

            PrimitiveReturningJob job = context.getBean(PrimitiveReturningJob.class);

            // LockingNotSupportedException は package-private のため、公開の親クラスとクラス名で確認する。
            assertThatThrownBy(job::run)
                    .isInstanceOf(LockException.class)
                    .hasMessageContaining("primitive")
                    .extracting(e -> e.getClass().getName())
                    .isEqualTo("net.javacrumbs.shedlock.spring.aop.LockingNotSupportedException");
            assertThat(executions).as("例外で本体は一度も実行されない").hasValue(0);
            assertThat(context.getBean(RecordingLockProvider.class).lockedNames)
                    .as("ロック取得より前に拒否される").isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class LockConfig {
        @Bean
        RecordingLockProvider lockProvider() {
            return new RecordingLockProvider();
        }
    }

    /** 取得したロック名を記録し、常にロック取得に成功するインメモリ LockProvider。 */
    static class RecordingLockProvider implements LockProvider {
        final List<String> lockedNames = new CopyOnWriteArrayList<>();

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            lockedNames.add(lockConfiguration.getName());
            return Optional.of(() -> { });
        }
    }

    /** 意図的にプリミティブを返す fixture（静的検査の走査対象からは除外している）。 */
    static class PrimitiveReturningJob {
        private final AtomicInteger executions;

        PrimitiveReturningJob(AtomicInteger executions) {
            this.executions = executions;
        }

        @SchedulerLock(name = "fixturePrimitiveReturn", lockAtMostFor = "PT1M")
        public int run() {
            return executions.incrementAndGet();
        }
    }
}
