package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.SystemConfig;
import com.ses.featureenablement.nf05.IntegrationHubRuntimeSwitch;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.AuditLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-D04（feature-enablement-console design §9.3、review P3-N2）: 閉鎖の transaction が barrier の行を {@code v1|OFF|0|<指紋>} に書き直し
 * {@code NF05_OUTBOUND_OFF} を監査し、commit の後に memory の {@code ready} を false にする。起動時の経路は起動時の値、実行時の判定は
 * runtime switch で分岐し、起動時の初期化が失敗した後に閉じた時は初期化を再試行しない。
 */
class IntegrationHubOutboundStartupBarrierTest {

    private static final String TENANT = "barrier-tenant";

    private ApiDeliveryMapper deliveries;
    private SystemConfigMapper configs;
    private AuditLogService audit;
    private PlatformTransactionManager manager;
    private IntegrationHubRuntimeSwitch runtime;

    @BeforeEach
    void setUp() {
        deliveries = mock(ApiDeliveryMapper.class);
        configs = mock(SystemConfigMapper.class);
        audit = mock(AuditLogService.class);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        runtime = mock(IntegrationHubRuntimeSwitch.class);
        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.NONE);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void 閉鎖で行をOFFに書き直し監査しcommitの後にreadyを外す() {
        SystemConfig row = new SystemConfig(IntegrationHubOutboundStartupBarrier.CONFIG_KEY, state("ON"), "synthetic");
        when(configs.selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY)).thenReturn(row);
        IntegrationHubOutboundStartupBarrier barrier = barrier(true);
        barrier.start();
        assertThat(barrier.isReady()).isTrue();

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        barrier.recordRuntimeOff();
        ArgumentCaptor<SystemConfig> written = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configs).updateById(written.capture());
        assertThat(written.getValue().getConfigValue()).isEqualTo(state("OFF"));
        ArgumentCaptor<AuditLog> audited = ArgumentCaptor.forClass(AuditLog.class);
        verify(audit).recordDomainEventRequired(audited.capture());
        assertThat(audited.getValue().getApplicationCode()).isEqualTo("NF05_OUTBOUND_OFF");
        assertThat(audited.getValue().getReferenceType()).isEqualTo("NF05");
        assertThat(audited.getValue().getReferenceId()).isZero();
        assertThat(barrier.isReady()).as("commit の前は memory を変えない").isTrue();
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(barrier.isReady()).as("commit の後は ready を外す（同じ process で開き直さない）").isFalse();
    }

    @Test
    void 閉鎖の記録はtransactionの外では拒否する() {
        assertThatThrownBy(() -> barrier(true).recordRuntimeOff()).isInstanceOf(IllegalStateException.class);
        verify(configs, never()).selectByIdForUpdate(anyString());
    }

    @Test
    void 起動時の経路は起動時の値で分岐し実行時の判定はruntime_switchで分岐する() {
        SystemConfig row = new SystemConfig(IntegrationHubOutboundStartupBarrier.CONFIG_KEY, state("ON"), "synthetic");
        when(configs.selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY)).thenReturn(row);
        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        IntegrationHubOutboundStartupBarrier barrier = barrier(true);
        barrier.start();
        verify(configs, never()).updateById(any(SystemConfig.class));
        assertThat(barrier.isReady()).as("起動時の値で初期化しても実行時に閉じていれば送らない").isFalse();

        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.TRANSIENT);
        assertThat(barrier.isReady()).isFalse();
        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.NONE);
        assertThat(barrier.isReady()).as("一時の閉鎖が解ければ同じ process で戻る").isTrue();
    }

    @Test
    void 起動時の初期化が失敗した後に閉じたら初期化を再試行せず行をQUARANTININGに書き換えない() {
        when(configs.selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY))
                .thenThrow(new IllegalStateException("synthetic DB failure"));
        IntegrationHubOutboundStartupBarrier barrier = barrier(true);
        barrier.start();
        verify(configs, times(1)).selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY);

        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        barrier.continueRecovery();
        verify(configs, times(1)).selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY);
        verify(configs, never()).updateById(any(SystemConfig.class));
        verify(audit, never()).recordDomainEventRequired(any());
    }

    @Test
    void OFFの起動は実行時の判定に依らず初期化を再試行してOFFを書く() {
        when(configs.selectByIdForUpdate(IntegrationHubOutboundStartupBarrier.CONFIG_KEY))
                .thenThrow(new IllegalStateException("synthetic DB failure")).thenReturn(null);
        when(runtime.outboundClosure()).thenReturn(IntegrationHubRuntimeSwitch.Closure.DEFINITIVE);
        IntegrationHubOutboundStartupBarrier barrier = barrier(false);
        barrier.start();
        barrier.continueRecovery();
        ArgumentCaptor<SystemConfig> inserted = ArgumentCaptor.forClass(SystemConfig.class);
        verify(configs).insert(inserted.capture());
        assertThat(inserted.getValue().getConfigValue()).isEqualTo(state("OFF"));
    }

    private IntegrationHubOutboundStartupBarrier barrier(boolean bootEnabled) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getExternalTransport().setEnabled(bootEnabled);
        properties.getTopology().setBoundTenantId(TENANT);
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        IntegrationHubOutboundStartupBarrier barrier = new IntegrationHubOutboundStartupBarrier(deliveries, audit, manager,
                Clock.fixed(Instant.parse("2026-12-10T00:00:00Z"), ZoneOffset.UTC), configs, properties, meters);
        barrier.setRuntimeSwitch(runtime);
        return barrier;
    }

    private static String state(String phase) {
        return "v1|" + phase + "|0|" + IntegrationHubDigest.sha256Hex(TENANT);
    }
}
