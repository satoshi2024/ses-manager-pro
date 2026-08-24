package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("REV-P1-002 Webhook SSRF 防御テスト")
class WebhookNotifierSsrfTest {

    @Test
    @DisplayName("クラウドメタデータ (169.254.169.254) へのWebhook送信が拒絶されること")
    void notifyNow_blocksCloudMetadata() {
        SystemConfigService config = Mockito.mock(SystemConfigService.class);
        when(config.getString(eq("notification.webhook-url"), eq(null)))
                .thenReturn("http://169.254.169.254/latest/meta-data/");
        when(config.getString(eq("notification.webhook-types"), eq("")))
                .thenReturn("SYSTEM");

        Notification notification = new Notification();
        notification.setType("SYSTEM");
        notification.setTitle("SSRF test");

        WebhookNotifier notifier = new WebhookNotifier(config, new RestTemplate(), false);
        boolean result = notifier.notifyNow(notification);
        assertFalse(result, "169.254.169.254 への送信は拒絶されること");
    }

    @Test
    @DisplayName("プライベートIP (10.0.0.1, 192.168.1.1) へのWebhook送信が拒絶されること")
    void notifyNow_blocksPrivateIps() {
        assertFalse(WebhookNotifier.isValidWebhookUrl("http://10.0.0.1/webhook", false));
        assertFalse(WebhookNotifier.isValidWebhookUrl("http://192.168.1.1/webhook", false));
        assertFalse(WebhookNotifier.isValidWebhookUrl("http://127.0.0.1/webhook", false));
    }

    @Test
    @DisplayName("正規の外部HTTPS Webhook URLが許可されること")
    void notifyNow_allowsValidHttpsUrl() {
        assertTrue(WebhookNotifier.isValidWebhookUrl("https://hooks.slack.com/services/T00/B00/X00", false));
    }
}
