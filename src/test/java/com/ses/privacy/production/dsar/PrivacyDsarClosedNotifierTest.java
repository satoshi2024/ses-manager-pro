package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FE-C05（feature-enablement-console design §7.5。review P3-21）: DSAR が閉じている間の未完了の請求は、日の始まりに privacy-admin へ件数と
 * 最も近い期限日だけを知らせる（受付番号・subject 参照を入れない）。開いている間は期限の通知の scheduler に任せて何もしない。
 */
class PrivacyDsarClosedNotifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private final PrivacyRequestMapper requests = mock(PrivacyRequestMapper.class);
    private final PrivacyDsarRecipientResolver recipients = mock(PrivacyDsarRecipientResolver.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final FeatureSwitches switches = mock(FeatureSwitches.class);

    @org.junit.jupiter.api.BeforeAll
    static void tableInfo() {
        // LambdaQueryWrapper の select が entity の列の情報を要する（Spring を起動しない単体試験）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), PrivacyRequest.class);
    }

    @Test
    void DSARが開いていれば何もしない() {
        when(switches.isEnabled(Feature.NF07_DSAR)).thenReturn(true);

        notifier().onBusinessDayStart(null, TODAY);

        verifyNoInteractions(requests, recipients, notifications);
    }

    @Test
    void 閉じている間は未完了の件数と最も近い期限日だけを日ごとに1回知らせる() {
        when(switches.isEnabled(Feature.NF07_DSAR)).thenReturn(false);
        when(requests.selectCount(any())).thenReturn(3L);
        PrivacyRequest nearest = new PrivacyRequest();
        nearest.setId(7L);
        nearest.setRequestNo("DSAR-SECRET-0007");
        nearest.setDueDate(LocalDate.of(2026, 12, 1));
        when(requests.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<PrivacyRequest> page = invocation.getArgument(0);
            page.setRecords(List.of(nearest));
            return page;
        });
        when(recipients.resolve("tenant-a")).thenReturn(List.of(11L, 12L));

        notifier().onBusinessDayStart(null, TODAY);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notifications, times(2)).publishToUser(any(), eq(PrivacyDsarClosedNotifier.NOTIFICATION_TYPE), anyString(),
                message.capture(), anyString(), eq(PrivacyDsarClosedNotifier.NOTIFICATION_TYPE + ":tenant-a:" + TODAY),
                eq(PrivacyDsarDeadlineScheduler.MENU_KEY));
        assertThat(message.getValue()).contains("3 件").contains("2026-12-01").doesNotContain("DSAR-SECRET-0007");
    }

    @Test
    void 未完了の請求が無ければ知らせない() {
        when(switches.isEnabled(Feature.NF07_DSAR)).thenReturn(false);
        when(requests.selectCount(any())).thenReturn(0L);

        notifier().onBusinessDayStart(null, TODAY);

        verify(notifications, never()).publishToUser(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString());
        verifyNoInteractions(recipients);
    }

    private PrivacyDsarClosedNotifier notifier() {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId("tenant-a");
        return new PrivacyDsarClosedNotifier(requests, recipients, notifications, oidc, switches);
    }
}
