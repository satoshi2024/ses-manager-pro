package com.ses.backup.quiesce;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 業務の {@code dataSource} を {@link BackupQuiesceAwareDataSource} で包み、停止の時の STOPPED の書込みを受け持つ
 * （design.md §6.5.1・§6.11。D-P2-1・D3-P3-1）。
 *
 * <p>static な {@code @Bean} で登録し、anchor は包む時に {@link ObjectProvider} で遅れて引く。gate が INERT（prod 以外の
 * {@code enabled=false}）なら包まない。
 *
 * <p>停止（{@link #postProcessBeforeDestruction}）: Spring は破棄の callback を包む前の bean（Hikari）に登録するため、包みの
 * {@code close()} は呼ばれない。この callback は通常の停止でも refresh の失敗の経路（{@code destroyBeans()}。
 * {@code ContextClosedEvent} は出ない）でも必ず通る。
 * <ol>
 *   <li>gate を SHUTDOWN にし、呼んだ thread が持つ permit（起動の失敗の時に main の thread が持つ startup permit）を返す。</li>
 *   <li>permit と書込み可能な接続が 0 になるのを最長 {@link #DRAIN_WAIT}（10 秒）待つ。</li>
 *   <li>0 になった場合だけ Hikari を明示に閉じ（後で Spring が呼ぶ close は何もしない）、その後で anchor が instance を
 *       STOPPED にする（I-5）。0 にならなければ STOPPED を書かず WARN {@code BACKUP_QUIESCE_STOP_UNCONFIRMED} を出し、停止済みの
 *       閾値（300 秒）に任せる（書込み中の処理が残るかもしれない instance を STOPPED にしない）。</li>
 * </ol>
 */
public class BackupQuiesceDataSourcePostProcessor implements DestructionAwareBeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(BackupQuiesceDataSourcePostProcessor.class);

    /** 停止の時に排水を待つ上限。 */
    static final Duration DRAIN_WAIT = Duration.ofSeconds(10);
    static final String DATA_SOURCE = "dataSource";

    /** 停止の時の待ち（試験は時計を差し替える）。 */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final ObjectProvider<BackupQuiesceLifecycleAnchor> anchorProvider;
    private final Duration drainWait;
    private final Sleeper sleeper;
    /** 包んだ前の DataSource（破棄の callback の対象）と、その時の anchor。 */
    private final Map<Object, BackupQuiesceLifecycleAnchor> wrapped =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public BackupQuiesceDataSourcePostProcessor(ObjectProvider<BackupQuiesceLifecycleAnchor> anchorProvider) {
        this(anchorProvider, DRAIN_WAIT, duration -> Thread.sleep(duration.toMillis()));
    }

    BackupQuiesceDataSourcePostProcessor(ObjectProvider<BackupQuiesceLifecycleAnchor> anchorProvider, Duration drainWait,
                                         Sleeper sleeper) {
        this.anchorProvider = Objects.requireNonNull(anchorProvider, "anchorProvider");
        this.drainWait = drainWait;
        this.sleeper = sleeper;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!DATA_SOURCE.equals(beanName) || !(bean instanceof DataSource dataSource)
                || bean instanceof BackupQuiesceAwareDataSource) {
            return bean;
        }
        BackupQuiesceLifecycleAnchor anchor = anchorProvider.getIfAvailable();
        if (anchor == null || !anchor.isActive()) {
            return bean;
        }
        wrapped.put(bean, anchor);
        return new BackupQuiesceAwareDataSource(dataSource, anchor.gate());
    }

    @Override
    public boolean requiresDestruction(Object bean) {
        return wrapped.containsKey(bean);
    }

    @Override
    public void postProcessBeforeDestruction(Object bean, String beanName) throws BeansException {
        BackupQuiesceLifecycleAnchor anchor = wrapped.remove(bean);
        if (anchor == null) {
            return;
        }
        BackupWriteGate gate = anchor.gate();
        gate.shutdown();
        gate.releaseCurrentThreadPermits();
        if (!awaitDrained(gate)) {
            log.warn("errorCode=BACKUP_QUIESCE_STOP_UNCONFIRMED 停止の時に permit・書込み可能な業務の接続が {} 秒で 0 に"
                            + "ならないため、instance を STOPPED にしません（permit={}、接続={}、保持中: {}）。停止済みの閾値で判定されます",
                    drainWait.getSeconds(), gate.permitCount(), gate.writableConnectionCount(), gate.describePermitHolders());
            return;
        }
        if (!closeDataSource(bean)) {
            log.warn("errorCode=BACKUP_QUIESCE_STOP_UNCONFIRMED 停止の時に業務の接続 pool を閉じられないため、instance を STOPPED に"
                    + "しません。停止済みの閾値で判定されます");
            return;
        }
        anchor.writeStopped();
    }

    private boolean awaitDrained(BackupWriteGate gate) {
        long deadline = gate.nanoTime() + drainWait.toNanos();
        while (!gate.isDrained()) {
            if (gate.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                sleeper.sleep(Duration.ofMillis(50));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return gate.isDrained();
            }
        }
        return true;
    }

    /** 業務の接続 pool を閉じる。閉じられない（閉じる口が無い・例外）なら false。 */
    private static boolean closeDataSource(Object dataSource) {
        if (!(dataSource instanceof AutoCloseable closeable)) {
            return false;
        }
        try {
            closeable.close();
            return true;
        } catch (Exception failure) {
            log.warn("停止の時に業務の接続 pool を閉じられませんでした（{}）", failure.getClass().getSimpleName());
            return false;
        }
    }
}
