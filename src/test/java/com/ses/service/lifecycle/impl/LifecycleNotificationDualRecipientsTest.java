package com.ses.service.lifecycle.impl;

import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W3-21a review P3-7（W3-21b）: 二者確認の「確認待ち」通知は、lifecycle menuの全利用者への配信をやめ、
 * 案件のtenantの有効な確認者（管理者・HR）とタスクの担当者だけへ個別に送る。
 */
class LifecycleNotificationDualRecipientsTest {

    private NotificationService notificationService;
    private SysUserMapper sysUserMapper;
    private LifecycleNotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        sysUserMapper = mock(SysUserMapper.class);
        service = new LifecycleNotificationServiceImpl(notificationService, sysUserMapper);
    }

    @Test
    void 確認待ちは案件tenantの有効な管理者とHRと担当者だけへ個別に送り全体配信しない() {
        when(sysUserMapper.selectActiveByRoleAndTenant("管理者", "tenant-a")).thenReturn(List.of(user(10L)));
        when(sysUserMapper.selectActiveByRoleAndTenant("HR", "tenant-a")).thenReturn(List.of(user(11L), user(12L)));

        service.notifyDualConfirmationSubmitted(task(20L), lifecycleCase(" tenant-a "), 55L);

        ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, times(4)).publishToUser(recipients.capture(), eq("DUAL_CONFIRMATION_PENDING"),
                anyString(), anyString(), eq("/lifecycle/30"), eq("lifecycle-dual-submitted-55"), eq("lifecycle"));
        assertThat(recipients.getAllValues()).containsExactly(10L, 11L, 12L, 20L);
        verify(notificationService, never()).publish(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString());
        verify(notificationService, never()).publish(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void 担当者が確認者でもあれば1回だけ送り通知本文に業務commentや案件titleを載せない() {
        when(sysUserMapper.selectActiveByRoleAndTenant("管理者", "tenant-a")).thenReturn(List.of());
        when(sysUserMapper.selectActiveByRoleAndTenant("HR", "tenant-a")).thenReturn(List.of(user(20L)));

        service.notifyDualConfirmationSubmitted(task(20L), lifecycleCase("tenant-a"), 55L);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(1)).publishToUser(eq(20L), eq("DUAL_CONFIRMATION_PENDING"), title.capture(),
                message.capture(), anyString(), anyString(), eq("lifecycle"));
        assertThat(title.getValue() + message.getValue()).contains("LC-W321B", "二者確認")
                .doesNotContain("氏名入りの案件タイトル");
    }

    @Test
    void 案件のtenantが無ければ確認者を検索せず担当者だけへ送る() {
        service.notifyDualConfirmationSubmitted(task(20L), lifecycleCase(null), 55L);

        verifyNoInteractions(sysUserMapper);
        verify(notificationService, times(1)).publishToUser(eq(20L), eq("DUAL_CONFIRMATION_PENDING"), anyString(),
                anyString(), anyString(), anyString(), eq("lifecycle"));
    }

    @Test
    void 担当者も確認者もいなければ誰にも送らない() {
        when(sysUserMapper.selectActiveByRoleAndTenant(anyString(), anyString())).thenReturn(List.of());

        service.notifyDualConfirmationSubmitted(task(null), lifecycleCase("tenant-a"), 55L);

        verify(notificationService, never()).publishToUser(anyLong(), any(), any(), any(), any(), any(), any());
        verify(notificationService, never()).publish(any(), any(), any(), any(), any(), any());
    }

    private static SysUser user(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        return user;
    }

    private static LifecycleTask task(Long assigneeUserId) {
        LifecycleTask task = LifecycleTask.builder().caseId(30L).taskCode("DUAL").taskName("二者確認")
                .evidenceType("DUAL_CONFIRMATION").status("IN_PROGRESS").assigneeUserId(assigneeUserId).build();
        task.setId(7L);
        return task;
    }

    private static LifecycleCase lifecycleCase(String tenantId) {
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(tenantId).caseNo("LC-W321B")
                .title("氏名入りの案件タイトル").status("ACTIVE").build();
        lcCase.setId(30L);
        return lcCase;
    }
}
