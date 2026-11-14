package com.ses.service;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.ExternalAccountReference;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.provider.ExternalAccountProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static com.ses.service.ExternalAccountRevocationStates.*;

/** provider呼出し専用。transactionを持たず、登録済みの1行ずつをCAS取得する。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalAccountRevocationDispatcher {
    private final ExternalAccountReferenceMapper references;
    private final ExternalAccountRevocationOutbox outbox;
    private final ExternalAccountProviderClient provider;
    private final FeatureSwitches switches;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED, rollbackFor = Exception.class)
    public int dispatchPending() {
        if (!switches.isEnabled(Feature.NF01)) return 0;
        String tenant = AccountingTenantContextHolder.requireTenantContext();
        int sent = 0;
        for (ExternalAccountReference row : references.selectOutboxForTenant(tenant, LocalDateTime.now(clock))) {
            if (!tenant.equals(row.getTenantId()) || row.getLegalEntityId() == null) continue;
            try {
                if (SENDING.equals(row.getExternalSyncStatus())) {
                    outbox.recoverExpiredLease(row, provider.supportsIdempotentRevoke());
                    continue;
                }
                Duration timeout = provider.revokeRequestTimeout();
                if (timeout == null || timeout.isNegative() || timeout.isZero()) timeout = Duration.ofSeconds(30);
                ExternalAccountReference claim = outbox.claim(row, LocalDateTime.now(clock).plus(timeout).plusSeconds(30));
                if (claim == null) continue;
                // claim commit後だけI/Oする。停止・応答後のDB障害はlease回収時にUNKNOWNへ倒す。
                boolean accepted;
                try { accepted = provider.requestRevoke(claim); }
                catch (RuntimeException ex) {
                    if (provider.supportsIdempotentRevoke()) outbox.finish(claim, false);
                    else outbox.markSendUnknown(claim);
                    continue;
                }
                outbox.finish(claim, accepted);
                if (accepted) sent++;
            } catch (RuntimeException ex) {
                log.error("外部アカウント失効送信を継続できません: referenceId={}, exceptionClass={}",
                        row.getId(), ex.getClass().getSimpleName());
            }
        }
        return sent;
    }
}
