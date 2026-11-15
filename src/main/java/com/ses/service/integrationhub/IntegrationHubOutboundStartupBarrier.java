package com.ses.service.integrationhub;

import com.ses.entity.AuditLog;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.service.AuditLogService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 再開前のID高水位を固定する。旧snapshotは通常再送へ戻さず、管理者の再確認を必須にする。 */
@Component
@Conditional(IntegrationHubOutboundCondition.class)
public class IntegrationHubOutboundStartupBarrier implements ApplicationRunner {
    private final ApiDeliveryMapper deliveries;
    private final AuditLogService audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private volatile boolean ready;
    private volatile long highWater;

    public IntegrationHubOutboundStartupBarrier(ApiDeliveryMapper deliveries, AuditLogService audit,
            PlatformTransactionManager manager, Clock clock) {
        this.deliveries = deliveries;
        this.audit = audit;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Override public void run(ApplicationArguments args) {
        highWater = deliveries.selectHighWater();
        for (int batch = 0; batch < 32; batch++) {
            if (quarantineBatch() == 0) { ready = true; return; }
        }
        throw new IllegalStateException("NF05の旧delivery隔離が上限に達しました。監査を確認して再起動してください");
    }

    public boolean isReady() { return ready; }
    public boolean isHistorical(Long id) { return id != null && id <= highWater; }

    /** 有効leaseは奪わない。期限後の旧CLAIMEDも送信前に同じ隔離経路へ収束する。 */
    public int quarantineBatch() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
        Integer changed = transactions.execute(transaction -> {
            int count = 0;
            Long last = null;
            for (var row : deliveries.selectHistoricalForQuarantine(highWater, now, 128)) {
                count += deliveries.quarantineHistorical(row.getId(), row.getVersion(), now, now.plusDays(90));
                last = row.getId();
            }
            if (count > 0) {
                AuditLog entry = new AuditLog();
                entry.setUsername("nf05-startup"); entry.setMethod("SYSTEM");
                entry.setUri("/integration-hub/startup-quarantine"); entry.setStatus(200);
                entry.setApplicationCode("NF05_STARTUP_QUARANTINE"); entry.setSuccessFlag(true);
                entry.setReferenceType("NF05"); entry.setReferenceId(last);
                entry.setActorType("SYSTEM"); entry.setConfirmationSource("SCHEDULER_POLL");
                entry.setCreatedAt(now);
                audit.recordDomainEventRequired(entry);
            }
            return count;
        });
        return changed == null ? 0 : changed;
    }
}
