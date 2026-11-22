package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 静止応答の専用の thread（{@code backup-quiesce-heartbeat}・{@code backup-quiesce-responder}。design.md §5.2）を1つ持つ。
 *
 * <p>{@code @Scheduled} を使わない（{@code APP_SCHEDULING_ENABLED=false} でも動かすため）。{@link ScheduledExecutorService}
 * は bean にしない（{@code SchedulingDisabledDrainTest} の既知一覧に理由つきで載せる）。daemon thread で、処理の例外は
 * 捕まえて次の周期へ回す（{@code ScheduledExecutorService} は例外で周期の実行を黙って止めるため）。
 */
final class BackupQuiesceTicker {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceTicker.class);

    private final String threadName;
    private volatile ScheduledExecutorService executor;

    BackupQuiesceTicker(String threadName) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
    }

    /** {@code initialDelay} の後、{@code period} ごとに {@code task} を実行する（前の実行が終わってから数える）。2回目以降の呼出しは何もしない。 */
    synchronized void start(Duration initialDelay, Duration period, Runnable task) {
        if (executor != null) {
            return;
        }
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        created.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                log.warn("backup の書込み静止の thread（{}）の処理で例外が起きました。次の周期で続けます（{}）", threadName,
                        failure.getClass().getSimpleName());
            }
        }, initialDelay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        executor = created;
    }

    boolean isRunning() {
        ScheduledExecutorService current = executor;
        return current != null && !current.isShutdown();
    }

    /** thread を止める（実行中の文は socket の timeout までに終わる）。最長 {@code wait} 待つ。 */
    synchronized void stop(Duration wait) {
        ScheduledExecutorService current = executor;
        if (current == null) {
            return;
        }
        current.shutdownNow();
        try {
            if (!current.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("backup の書込み静止の thread（{}）が {} ms で止まりませんでした", threadName, wait.toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
