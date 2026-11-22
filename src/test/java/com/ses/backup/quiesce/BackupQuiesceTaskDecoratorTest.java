package com.ses.backup.quiesce;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-006（design.md §6.7）: {@code taskExecutor} の task の包みは、閉じる前（または permit を持つ thread）の投入を
 * 投入時に数えて排水の対象にし、閉じた後の投入は実行の開始で待たずに保留し、開いた時に投入し直させる。
 * {@code CallerRunsPolicy} で呼出し元が実行する場合も待たない。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BackupQuiesceTaskDecoratorTest {

    private final BackupWriteGate gate = BackupQuiesceTestSupport.openGate(false, () -> 0L, Clock.systemUTC());
    private final BackupQuiesceTaskDecorator decorator = new BackupQuiesceTaskDecorator(() -> gate, () -> null);
    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    @AfterEach
    void shutdown() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
    }

    private ThreadPoolTaskExecutor executor(int threads, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("decorator-test-");
        executor.setRejectedExecutionHandler(BackupQuiesceTaskDecorator.rejectedExecutionHandler());
        executor.setTaskDecorator(decorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        executors.add(executor);
        return executor;
    }

    @Test
    void 閉じる前の投入はqueueの中でも数え実行するthreadが引き継いで終わりに返す() throws Exception {
        AtomicBoolean heldWhileRunning = new AtomicBoolean();
        Runnable decorated = decorator.decorate(() -> heldWhileRunning.set(gate.currentThreadHoldsPermit()));

        assertThat(gate.permitCount()).as("投入した時点で数える").isEqualTo(1);
        BackupQuiesceTestSupport.beginClosing(gate, 1);
        assertThat(gate.isDrained()).as("queue の中の task が終わるまで排水は終わらない").isFalse();

        CompletableFuture.runAsync(decorated).get(5, TimeUnit.SECONDS);

        assertThat(heldWhileRunning).isTrue();
        assertThat(gate.permitCount()).isZero();
        assertThat(gate.isDrained()).isTrue();
    }

    @Test
    void 閉じた後の投入は実行の開始で待たずに保留し開いた時に投入し直させる() throws Exception {
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        List<Runnable> redispatched = new CopyOnWriteArrayList<>();
        gate.setDispatcher(redispatched::add);
        AtomicBoolean ran = new AtomicBoolean();
        Runnable original = () -> ran.set(true);

        Runnable decorated = decorator.decorate(original);
        assertThat(gate.permitCount()).as("閉じた後は数えない").isZero();
        long start = System.nanoTime();
        CompletableFuture.runAsync(decorated).get(5, TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(ran).as("静止中は実行しない").isFalse();
        assertThat(gate.pendingTaskCount()).isEqualTo(1);

        BackupQuiesceTestSupport.reopen(gate, 1);
        assertThat(redispatched).containsExactly(original);
        decorator.decorate(redispatched.get(0)).run();
        assertThat(ran).isTrue();
        assertThat(gate.permitCount()).isZero();
    }

    @Test
    void permitを持つthreadの閉じた後の投入は排水の対象として数える() {
        BackupWritePermit outer = gate.tryAcquire(BackupWritePermit.Kind.HTTP, "POST /api/x");
        BackupQuiesceTestSupport.beginClosing(gate, 1);
        try {
            Runnable decorated = decorator.decorate(() -> { });
            assertThat(gate.permitCount()).isEqualTo(2);
            outer.close();
            assertThat(gate.isDrained()).isFalse();
            decorated.run();
        } finally {
            outer.close();
        }
        assertThat(gate.isDrained()).isTrue();
    }

    @Test
    void CallerRunsPolicyで呼出し元が実行する閉じた後のtaskも待たずに戻る() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            awaitQuietly(block);
        });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> { });
        BackupQuiesceTestSupport.close(gate, 1, Duration.ofMinutes(5));
        AtomicBoolean ran = new AtomicBoolean();

        long start = System.nanoTime();
        executor.execute(() -> ran.set(true));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(ran).as("呼出し元の thread（Tomcat 等）で静止中に実行しない").isFalse();
        assertThat(gate.pendingTaskCount()).isEqualTo(1);
        block.countDown();
    }

    @Test
    void executorの停止の後に捨てたtaskは数えたpermitを返す() {
        ThreadPoolTaskExecutor executor = executor(1, 1);
        executor.shutdown();

        executor.execute(() -> { });

        assertThat(gate.permitCount()).as("捨てた task の permit を残さない").isZero();
    }

    @Test
    void INERTのgateとgateの無いcontextではtaskをそのまま返す() {
        Runnable task = () -> { };
        BackupWriteGate inert = new BackupWriteGate(BackupWriteGate.Mode.INERT, false, Duration.ofSeconds(20), 10);
        assertThat(new BackupQuiesceTaskDecorator(() -> inert, () -> null).decorate(task)).isSameAs(task);
        assertThat(new BackupQuiesceTaskDecorator(() -> null, () -> null).decorate(task)).isSameAs(task);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
