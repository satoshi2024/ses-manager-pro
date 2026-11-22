package com.ses.featureenablement.signoff;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.service.FeatureEnablementWriteSupport;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffMapper;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 終わった署名の氏名・元の file 名・文書の消去（feature-enablement-console design §6.6。J-14 の3年。RB §4.4 の D17）。
 *
 * <ul>
 *   <li>日次（03:40 JST）。{@code purge_after} を過ぎた {@code SUPERSEDED}・{@code EXPIRED}・{@code REVOKED}・{@code REJECTED} の行の
 *       氏名と元の file 名と保存名を NULL にして {@code PURGED} にし（列は {@code UpdateWrapper.set(col, null)} で名指しする。
 *       review P3-20）、commit の後に文書の実体と metadata を消す。SHA-256・参照・日付・役割は残す。</li>
 *   <li>保全 hold の gate（{@link PrivacyHoldPathGuard}、経路 {@link PrivacyHoldPath#D17}）を実行ごとに1回照会し、氏名・文書の
 *       data element が hold の対象か判定できなければ、その回は消さない。gate が無ければ何も消さない。</li>
 *   <li>行ごとに別の transaction（世代の行を lock し、履歴と監査を書く）。設定の tenant の行だけを扱う。</li>
 * </ul>
 * 機能のスイッチで止めない（保存期間の規則で、NF07 は hold の gate の判定だけに使う。{@code FeatureSwitchRouteCoverageTest} の
 * UNGATED_SCHEDULERS）。
 */
@Slf4j
@Component
public class FeatureSignOffPurgeScheduler {

    /** 1回に扱う行の上限。 */
    static final int BATCH_LIMIT = 200;
    /** 消す data element（V203 の catalog。subject_attribution は NOT_ATTRIBUTABLE）。 */
    public static final List<String> DATA_ELEMENTS = List.of("FEATURE_SIGN_OFF_SIGNER_NAME", "FEATURE_SIGN_OFF_DOCUMENT");
    private static final Set<String> PURGEABLE = Set.of("SUPERSEDED", "EXPIRED", "REVOKED", "REJECTED");

    private final FeatureSignOffMapper signOffMapper;
    private final FeatureEnablementWriteSupport write;
    private final FileStorageService fileStorage;
    private final ObjectProvider<PrivacyHoldPathGuard> guards;
    private final FeatureEnablementTenant tenant;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public FeatureSignOffPurgeScheduler(FeatureSignOffMapper signOffMapper, FeatureEnablementWriteSupport write,
                                        FileStorageService fileStorage, ObjectProvider<PrivacyHoldPathGuard> guards,
                                        FeatureEnablementTenant tenant, PlatformTransactionManager transactionManager,
                                        Clock clock) {
        this.signOffMapper = signOffMapper;
        this.write = write;
        this.fileStorage = fileStorage;
        this.guards = guards;
        this.tenant = tenant;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    @Scheduled(cron = "${app.feature-enablement.sign-off-purge-cron:0 40 3 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "featureSignOffPurge", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void purge() {
        try {
            int purged = purgeDue();
            if (purged > 0) {
                log.info("[機能の開放] 終わった署名の氏名と文書を消去しました: count={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 署名の消去に失敗しました: errorCode=FEATURE_SIGN_OFF_PURGE_FAILED exceptionClass={}",
                    e.getClass().getSimpleName());
        }
    }

    /** 期日を過ぎた行を消去する（消した件数）。 */
    public int purgeDue() {
        LocalDate today = FeatureEvaluationContext.businessDate(clock);
        List<FeatureSignOff> due = signOffMapper.selectPurgeDue(today, BATCH_LIMIT).stream()
                .filter(row -> tenant.tenantId().equals(row.getTenantId())).toList();
        if (due.isEmpty()) {
            return 0;
        }
        PrivacyHoldPathGuard guard = guards == null ? null : guards.getIfAvailable();
        if (guard == null) {
            log.warn("[機能の開放] 保全 hold の gate が無いため署名の消去を見送りました: errorCode=FEATURE_SIGN_OFF_PURGE_SKIPPED");
            return 0;
        }
        PrivacyHoldPathGuard.Batch batch = guard.openBatch(PrivacyHoldPath.D17, tenant.tenantId());
        try {
            PrivacyHoldGate.Verdict verdict = verdict(batch);
            if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                batch.skip(verdict, due.size());
                return 0;
            }
            int purged = 0;
            for (FeatureSignOff row : due) {
                Boolean done = transactions.execute(status -> purgeOne(row.getId()));
                if (Boolean.TRUE.equals(done)) {
                    purged++;
                }
            }
            return purged;
        } finally {
            batch.close();
        }
    }

    /** 氏名・文書の data element のどちらかが hold の対象か判定できなければ止める。 */
    static PrivacyHoldGate.Verdict verdict(PrivacyHoldPathGuard.Batch batch) {
        for (String element : DATA_ELEMENTS) {
            PrivacyHoldGate.Verdict verdict = batch.elementVerdict(element);
            if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                return verdict;
            }
        }
        return PrivacyHoldGate.Verdict.ALLOW;
    }

    private boolean purgeOne(long id) {
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.scheduler());
        FeatureSignOff row = signOffMapper.selectByIdForUpdate(id);
        if (row == null || !PURGEABLE.contains(row.getStatus()) || row.getPurgeAfter() == null
                || row.getPurgeAfter().isAfter(context.today())) {
            write.finish(context);
            return false;
        }
        UpdateWrapper<FeatureSignOff> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("status", row.getStatus()).eq("version", row.getVersion())
                .set("signer_name", null).set("original_name", null).set("stored_name", null)
                .set("status", "PURGED").set("purged_at", context.now()).set("version", row.getVersion() + 1);
        if (signOffMapper.update(null, update) != 1) {
            write.finish(context);
            return false;
        }
        write.event(context, "SIGN_OFF", row.getKindCode(), row.getId(), "PURGED", row.getStatus(), "PURGED", null, null);
        write.audit(context, "FEATURE_SIGN_OFF_PURGED", row.getId(),
                "{\"signOff\":" + row.getId() + ",\"status\":\"" + row.getStatus() + "\"}",
                "{\"signOff\":" + row.getId() + ",\"status\":\"PURGED\"}");
        write.finish(context);
        deleteAfterCommit(row.getTenantId(), row.getStoredName());
        return true;
    }

    /** commit の後に文書の実体と metadata を消す（失敗は孤児の清掃が消す。行は既に保存名を持たない）。 */
    private void deleteAfterCommit(String tenantId, String storedName) {
        if (storedName == null) {
            return;
        }
        Runnable delete = () -> {
            try {
                AccountingTenantContextHolder.runWithTenant(tenantId, () -> fileStorage.delete(storedName));
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 消去した署名の文書の実体を消せません（孤児の清掃が消す）: exceptionClass={}",
                        e.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
