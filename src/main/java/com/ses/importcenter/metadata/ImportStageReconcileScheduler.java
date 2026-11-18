package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 毎時17分に、取込stage照合台帳のうち{@link ImportStageReconciler#SAFETY_WINDOW}を過ぎたものを照合・回収する。
 *
 * <p>{@code app.scheduling.enabled=false}（test profile等）ではSchedulingConfigごと無効になり実行されない。
 * 台帳が空なら走査だけで終わりDBへは問い合わせない。ShedLockにより1 instanceだけが実行するため、upload rootが
 * instanceごとのlocal directoryである多instance構成では、実行しなかったinstanceの台帳は次にlockを得るまで残る
 * （FileCleanupSchedulerと同じ制約。初回本番は単一instanceまたはsticky session前提）。
 */
@Component
public class ImportStageReconcileScheduler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportStageReconcileScheduler.class);
    private final ImportStageReconciler reconciler;

    public ImportStageReconcileScheduler(ImportStageReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(cron = "0 17 * * * *")
    @SchedulerLock(name = "importStageReconcileHourly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void reconcileHourly() {
        try {
            ImportStageReconciler.Result result = reconciler.reconcileDue();
            if (result.touched()) {
                log.info("[取込] stage照合バッチ完了: reclaimed={} committed={} pending={} failed={} manual={}",
                        result.reclaimed(), result.committed(), result.pending(), result.failed(), result.manual());
            }
        } catch (RuntimeException failure) {
            log.error("[取込] stage照合バッチが失敗しました: exceptionType={}", LogRedaction.exceptionType(failure));
        }
    }
}
