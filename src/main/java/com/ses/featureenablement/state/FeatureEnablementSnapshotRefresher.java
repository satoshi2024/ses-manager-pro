package com.ses.featureenablement.state;

import com.ses.featureenablement.config.FeatureEnablementProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 他の instance の変更を拾う世代の poll（design §3.3）。専用の daemon thread で {@code app.feature-enablement.refresh-interval}
 * （既定5秒）ごとに世代の1行を読み、変わっていれば snapshot を読み直す。変わらなくても最後に読めた時刻を更新する。
 *
 * <p>{@code @Scheduled} を使わない: {@code app.scheduling.enabled=false} で scheduler を一括で止めた時（全停止配備の drain・backup の
 * 静止）に、全ての開放単位が古さで閉じないため（読むだけで書かない。RB-QUIESCE の {@code BackupQuiesceTicker} と同じ扱い。
 * {@code SchedulingDisabledDrainTest} の既知の一覧に理由つきで登録する）。DB の源で {@code refresh-thread-enabled} が true の時だけ
 * 起動する（試験では起動せず、試験が明示的に読み直す）。最後に読めた時刻から max-staleness を超えたら WARN
 * {@code errorCode=FEATURE_ENABLEMENT_SNAPSHOT_STALE} を出す（状態が変わった時だけ）。</p>
 */
@Slf4j
@Component
public class FeatureEnablementSnapshotRefresher implements SmartLifecycle {

    private final FeatureEnablementSnapshotHolder holder;
    private final FeatureEnablementProperties properties;
    private final Clock clock;
    private volatile ScheduledExecutorService executor;
    private volatile boolean staleReported;

    public FeatureEnablementSnapshotRefresher(FeatureEnablementSnapshotHolder holder,
                                              FeatureEnablementProperties properties, Clock clock) {
        this.holder = holder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (executor != null || !properties.databaseSource() || !properties.isRefreshThreadEnabled()) {
            return;
        }
        ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "feature-enablement-refresher");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.getRefreshInterval().toMillis();
        created.scheduleWithFixedDelay(this::pollOnce, interval, interval, TimeUnit.MILLISECONDS);
        executor = created;
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        executor = null;
        if (running != null) {
            running.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    /** 1回の poll（例外は捕まえて次の周期へ回す。周期が黙って止まらない）。 */
    void pollOnce() {
        try {
            holder.poll();
            boolean stale = Duration.between(holder.get().verifiedAt(), clock.instant())
                    .compareTo(properties.getMaxStaleness()) > 0;
            if (stale && !staleReported) {
                log.warn("[機能の開放] 開放状態を読めない時間が上限を超えたため、DB で管理する全ての開放単位を閉じています: "
                        + "errorCode=FEATURE_ENABLEMENT_SNAPSHOT_STALE");
            } else if (!stale && staleReported) {
                log.info("[機能の開放] 開放状態を再び読めるようになりました");
            }
            staleReported = stale;
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 世代の poll に失敗しました: exceptionClass={}", e.getClass().getSimpleName());
        }
    }
}
