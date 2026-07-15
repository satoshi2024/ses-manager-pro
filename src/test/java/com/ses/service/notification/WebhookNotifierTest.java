package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WebhookNotifierの単体テスト。
 * URL未設定時のスキップ、対象外種別のスキップ、送信失敗時に例外が上位へ伝播しないこと、
 * および非同期実行であること（@Asyncアノテーション付与）を確認する。
 */
@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private RestTemplate restTemplate;

    private WebhookNotifier webhookNotifier() {
        return new WebhookNotifier(systemConfigService, restTemplate);
    }

    @Test
    void notify_URL未設定時はスキップされる() {
        when(systemConfigService.getString("notification.webhook-url", null)).thenReturn(null);

        Notification notification = notification("CONTRACT_END");
        assertDoesNotThrow(() -> webhookNotifier().notify(notification));

        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void notify_対象種別に含まれない場合はスキップされる() {
        when(systemConfigService.getString("notification.webhook-url", null)).thenReturn("https://hooks.example.com/webhook");
        when(systemConfigService.getString("notification.webhook-types", "")).thenReturn("CONTRACT_END,PROJECT_URGENT");

        Notification notification = notification("MAIL_FAILED");
        assertDoesNotThrow(() -> webhookNotifier().notify(notification));

        verify(restTemplate, never()).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test
    void notify_対象種別なら送信される() {
        when(systemConfigService.getString("notification.webhook-url", null)).thenReturn("https://hooks.example.com/webhook");
        when(systemConfigService.getString("notification.webhook-types", "")).thenReturn("CONTRACT_END,PROJECT_URGENT");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        Notification notification = notification("CONTRACT_END");
        webhookNotifier().notify(notification);

        verify(restTemplate, times(1)).postForEntity(eq("https://hooks.example.com/webhook"), any(), eq(String.class));
    }

    @Test
    void notify_送信失敗時は例外が上位へ伝播しない() {
        when(systemConfigService.getString("notification.webhook-url", null)).thenReturn("https://hooks.example.com/webhook");
        when(systemConfigService.getString("notification.webhook-types", "")).thenReturn("CONTRACT_END");
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        Notification notification = notification("CONTRACT_END");
        assertDoesNotThrow(() -> webhookNotifier().notify(notification));
    }

    @Test
    void notify_非同期メソッドとして定義されている() throws NoSuchMethodException {
        Method method = WebhookNotifier.class.getMethod("notify", Notification.class);
        assertTrue(method.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class),
                "notify()には@Asyncが付与されている必要がある");
        assertTrue(Modifier.isPublic(method.getModifiers()));
    }

    private Notification notification(String type) {
        Notification notification = new Notification();
        notification.setType(type);
        notification.setTitle("タイトル");
        notification.setMessage("本文");
        notification.setLinkUrl("/contracts/1");
        return notification;
    }
}
