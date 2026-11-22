package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * snapshot の保持（design §3.2・§3.3）。{@link AtomicReference} で持ち、読取りは lock を取らない。
 *
 * <ul>
 *   <li>最初の読込みは初期化（{@code @PostConstruct}。DB の初期化の後）で同期して1回だけ行う。{@code FeatureSwitches} はこの holder に
 *       依存するため、{@code FeatureSwitches.logStates}（{@code @PostConstruct}）と起動時検査（{@code SmartInitializingSingleton}）は
 *       読込みの後の状態を見る。読めなければ空の snapshot（全て閉）で WARN を出し、refresher が読み直す。</li>
 *   <li>同じ instance の書込みは {@link #reloadAfterCommit} で commit の後に読み直して差し替える（要求の応答より前）。読み直しに
 *       失敗したら、変更した開放単位を一時の閉鎖（{@link FeatureEnablementOverlay#REFRESH_PENDING}）にし、次の poll に任せる。</li>
 *   <li>property の源（dev・test の既定）では何も読まない（{@link #get} は空の snapshot を返す）。</li>
 * </ul>
 */
@Slf4j
@Component
@DependsOnDatabaseInitialization
public class FeatureEnablementSnapshotHolder {

    private final FeatureEnablementSnapshotLoader loader;
    private final FeatureEnablementTenant tenant;
    private final FeatureEnablementProperties properties;
    private final FeatureEnablementOverlay overlay;
    /** 差替えの通知先（{@code FeatureSwitches} が実効値の変化を log に出す。design §3.4）。 */
    private final List<Consumer<FeatureEnablementSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private final AtomicReference<FeatureEnablementSnapshot> current = new AtomicReference<>();

    public FeatureEnablementSnapshotHolder(FeatureEnablementSnapshotLoader loader, FeatureEnablementTenant tenant,
                                           FeatureEnablementProperties properties, FeatureEnablementOverlay overlay,
                                           Clock clock) {
        this.loader = loader;
        this.tenant = tenant;
        this.properties = properties;
        this.overlay = overlay;
        this.clock = clock;
    }

    @PostConstruct
    void loadOnStartup() {
        if (properties == null || !properties.databaseSource()) {
            return;
        }
        if (!reload()) {
            log.warn("[機能の開放] 起動時に開放状態を読めないため、DB で管理する全ての開放単位を閉として起動します（読めれば戻ります）");
        }
    }

    /** 現在の snapshot（一度も読めていなければ空の snapshot。全て閉）。 */
    public FeatureEnablementSnapshot get() {
        FeatureEnablementSnapshot snapshot = current.get();
        return snapshot != null ? snapshot
                : FeatureEnablementSnapshot.empty(clock.instant(), properties == null || properties.isAllowed());
    }

    /** 一度でも DB を読めたか。 */
    public boolean loaded() {
        return current.get() != null;
    }

    /** DB から読み直して差し替える。読めなければ false（現在の snapshot を残す。古さで閉じる）。 */
    public boolean reload() {
        FeatureEnablementSnapshot loadedSnapshot;
        try {
            loadedSnapshot = loader.load(tenant.tenantId());
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 開放状態を読めません: errorCode=FEATURE_ENABLEMENT_SNAPSHOT_LOAD_FAILED exceptionClass={}",
                    e.getClass().getSimpleName());
            return false;
        }
        replace(loadedSnapshot);
        overlay.clearAll(FeatureEnablementOverlay.REFRESH_PENDING);
        return true;
    }

    /**
     * 世代の1行を読み、変わっていれば読み直す。変わらなければ最後に読めた時刻だけを更新する（review P3-6）。読めなければ false。
     */
    public boolean poll() {
        FeatureEnablementSnapshot snapshot = current.get();
        long generation;
        try {
            generation = loader.readGeneration(tenant.tenantId());
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 世代を読めません: errorCode=FEATURE_ENABLEMENT_SNAPSHOT_POLL_FAILED exceptionClass={}",
                    e.getClass().getSimpleName());
            return false;
        }
        if (snapshot == null || snapshot.generation() != generation) {
            return reload();
        }
        current.compareAndSet(snapshot, snapshot.verifiedAt(clock.instant()));
        return true;
    }

    /**
     * 書込みの transaction の commit の後に読み直す（{@code TransactionSynchronization.afterCommit}）。transaction の外なら直ちに読み直す。
     * 読み直しに失敗したら変更した開放単位を一時の閉鎖にする。
     */
    public void reloadAfterCommit(Collection<Feature> changed) {
        List<Feature> features = changed == null ? List.of() : List.copyOf(changed);
        if (properties == null || !properties.databaseSource()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            reloadOrClose(features);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reloadOrClose(features);
            }
        });
    }

    /** snapshot の差替えの通知先を足す（呼出しは差し替えた thread で同期に行う。例外は log だけ）。 */
    public void addListener(Consumer<FeatureEnablementSnapshot> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** snapshot を差し替える（試験と、読み直した snapshot の反映）。 */
    public void replace(FeatureEnablementSnapshot snapshot) {
        current.set(snapshot);
        for (Consumer<FeatureEnablementSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException e) {
                log.warn("[機能の開放] snapshot の差替えの通知に失敗しました: exceptionClass={}", e.getClass().getSimpleName());
            }
        }
    }

    private void reloadOrClose(List<Feature> features) {
        if (!reload()) {
            for (Feature feature : features) {
                overlay.close(feature, FeatureEnablementOverlay.REFRESH_PENDING);
            }
        }
    }
}
