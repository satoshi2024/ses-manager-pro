package com.ses.service;

import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.ExternalAccountReference;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.provider.ExternalAccountProviderClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.ses.service.ExternalAccountRevocationStates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** U10: 送信CAS、機能停止、provider例外とACK前crashの処理境界。 */
@ExtendWith(MockitoExtension.class)
class ExternalAccountRevocationDispatcherTest {
    @Mock ExternalAccountReferenceMapper references;
    @Mock ExternalAccountRevocationOutbox outbox;
    @Mock ExternalAccountProviderClient provider;
    @Mock FeatureSwitches switches;
    ExternalAccountRevocationDispatcher dispatcher;
    @BeforeEach void setUp() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
        dispatcher = new ExternalAccountRevocationDispatcher(references, outbox, provider, switches,
                Clock.fixed(Instant.parse("2026-11-14T01:00:00Z"), ZoneOffset.UTC));
    }
    @AfterEach void clear() { AccountingTenantContextHolder.clear(); }

    @Test void NF01が無効ならDBもproviderも呼ばない() {
        assertThat(dispatcher.dispatchPending()).isZero();
        verifyNoInteractions(references, outbox, provider);
    }

    @Test void CASを取得できた行だけ送信し受理後にSENTへ進める() {
        ExternalAccountReference row = due();
        when(switches.isEnabled(Feature.NF01)).thenReturn(true);
        when(references.selectOutboxForTenant(eq("tenant-a"), any())).thenReturn(List.of(row));
        when(outbox.claim(eq(row), any())).thenReturn(row);
        when(provider.requestRevoke(row)).thenReturn(true);
        assertThat(dispatcher.dispatchPending()).isEqualTo(1);
        var order = inOrder(outbox, provider);
        order.verify(outbox).claim(eq(row), any()); order.verify(provider).requestRevoke(row); order.verify(outbox).finish(row, true);
    }

    @Test void CAS競合の敗者は送信しない() {
        when(switches.isEnabled(Feature.NF01)).thenReturn(true);
        when(references.selectOutboxForTenant(eq("tenant-a"), any())).thenReturn(List.of(due()));
        dispatcher.dispatchPending();
        verify(provider, never()).requestRevoke(any());
    }

    @Test void 非冪等providerの通信例外はUNKNOWNにして再送しない() {
        ExternalAccountReference row = due();
        when(switches.isEnabled(Feature.NF01)).thenReturn(true);
        when(references.selectOutboxForTenant(eq("tenant-a"), any())).thenReturn(List.of(row));
        when(outbox.claim(eq(row), any())).thenReturn(row);
        when(provider.requestRevoke(row)).thenThrow(new IllegalStateException("synthetic timeout"));
        dispatcher.dispatchPending();
        verify(outbox).markSendUnknown(row);
        verify(outbox, never()).finish(any(), anyBoolean());
    }

    @Test void 送信中の停止回収はproviderの冪等宣言を確認し同じ巡回で送信しない() {
        ExternalAccountReference row = due(); row.setExternalSyncStatus(SENDING);
        when(switches.isEnabled(Feature.NF01)).thenReturn(true);
        when(references.selectOutboxForTenant(eq("tenant-a"), any())).thenReturn(List.of(row));
        dispatcher.dispatchPending();
        verify(outbox).recoverExpiredLease(row, false);
        verify(provider, never()).requestRevoke(any());
    }

    @Test void 他tenantの候補は送信しない() {
        ExternalAccountReference row = due(); row.setTenantId("tenant-b");
        when(switches.isEnabled(Feature.NF01)).thenReturn(true);
        when(references.selectOutboxForTenant(eq("tenant-a"), any())).thenReturn(List.of(row));
        dispatcher.dispatchPending();
        verifyNoInteractions(provider, outbox);
    }

    private ExternalAccountReference due() {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId("tenant-a").legalEntityId(2L)
                .externalSyncStatus(REQUESTED).status("PENDING_CONFIRMATION").version(1).idempotencyKey("lifecycle-resign:1:5:g1").build();
        row.setId(5L); return row;
    }
}
