package com.ses.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.entity.NotificationOutbox;
import com.ses.entity.ReportDelivery;
import com.ses.entity.ReportRun;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.ReportDeliveryMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import com.ses.service.report.ReportDeliveryNotificationBridge;
import com.ses.service.report.impl.ReportDeliveryIssueServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDeliveryIssueServiceImplTest {

    private ReportDeliveryMapper deliveryMapper;
    private NotificationOutboxMapper outboxMapper;
    private ReportDeliveryNotificationBridge bridge;
    private ReportDeliveryIssueServiceImpl service;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        deliveryMapper = mock(ReportDeliveryMapper.class);
        outboxMapper = mock(NotificationOutboxMapper.class);
        bridge = mock(ReportDeliveryNotificationBridge.class);
        AccountingTimezoneResolver timezoneResolver = mock(AccountingTimezoneResolver.class);
        when(timezoneResolver.now("default")).thenReturn(LocalDateTime.of(2026, 9, 17, 12, 0));
        when(deliveryMapper.insert(any(ReportDelivery.class))).thenAnswer(invocation -> {
            ReportDelivery delivery = invocation.getArgument(0);
            delivery.setId(7L);
            return 1;
        });
        when(deliveryMapper.update(any(ReportDelivery.class), any())).thenReturn(1);
        service = new ReportDeliveryIssueServiceImpl(
                deliveryMapper, outboxMapper, bridge, new ObjectMapper(), timezoneResolver);
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void duplicate通知は受信者suffix付きdedupeから既存outboxへ収束する() {
        ReportRun run = run();
        ReportRecipientPreview recipient = recipient();
        when(bridge.publish(2L, "MANAGEMENT_REPORT", "月次管理レポート", "snapshotを確認できます（ダウンロード時に再認証が必要です）。",
                "/api/management-reports/deliveries/7/download", "REPORT:10:2:a1", "management-report"))
                .thenReturn(null);
        NotificationOutbox existing = new NotificationOutbox();
        existing.setId(88L);
        existing.setTenantId("default");
        when(outboxMapper.selectByDedupeKey("default", "REPORT:10:2:a1#u2")).thenReturn(existing);

        ReportDelivery result = service.issue(run, null, recipient, null);

        assertThat(result.getDeliveryStatus()).isEqualTo("ENQUEUED");
        assertThat(result.getNotificationOutboxId()).isEqualTo(88L);
        verify(outboxMapper).selectByDedupeKey("default", "REPORT:10:2:a1#u2");
        verify(outboxMapper, never()).selectByDedupeKey("default", "REPORT:10:2:a1");
    }

    @Test
    void 取消後の再issue失敗は旧outbox参照を残さない() {
        ReportDelivery existing = new ReportDelivery();
        existing.setId(7L);
        existing.setTenantId("default");
        existing.setAttemptCount(1);
        existing.setNotificationOutboxId(77L);
        existing.setDeliveryStatus("CANCELLED");
        when(bridge.publish(any(), any(), any(), any(), any(), any(), any())).thenReturn(null);

        ReportDelivery result = service.issue(run(), existing, recipient(), null);

        assertThat(result.getDeliveryStatus()).isEqualTo("RETRY");
        assertThat(result.getNotificationOutboxId()).isNull();
        assertThat(result.getNotificationDedupeKey()).isEqualTo("REPORT:10:2:a2");
    }

    @Test
    void tenant付き更新が0件ならforeign行を成功扱いにしない() {
        when(deliveryMapper.update(any(ReportDelivery.class), any())).thenReturn(0);
        ReportDelivery existing = new ReportDelivery();
        existing.setId(7L);
        existing.setTenantId("default");
        existing.setDeliveryStatus("CANCELLED");

        assertThatThrownBy(() -> service.issue(run(), existing, recipient(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("更新に失敗");
    }

    private ReportRun run() {
        ReportRun run = new ReportRun();
        run.setId(10L);
        run.setTenantId("default");
        return run;
    }

    private ReportRecipientPreview recipient() {
        return new ReportRecipientPreview(2L, "マネージャー", "ALLOW", "SCOPE_MATCH", "scope");
    }
}
