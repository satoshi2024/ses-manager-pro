package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生成済み通知をSlack互換Incoming Webhookへ転送するコンポーネント。
 * <p>
 * 既存の通知生成ロジック（{@code NotificationServiceImpl}等）には一切影響を与えず、
 * 「保存済み通知をどこかへ追加で転送するか」のみを担当する。
 * <ul>
 *   <li>{@code notification.webhook-url}が未設定の場合は送信をスキップする</li>
 *   <li>{@code notification.webhook-types}（カンマ区切り）に含まれる種別のみ転送する</li>
 *   <li>送信は非同期（{@code @Async}）で行い、失敗時は例外を上位へ伝播させずログ出力のみとする</li>
 * </ul>
 */
@Slf4j
@Component
public class WebhookNotifier {

    private static final String KEY_WEBHOOK_URL = "notification.webhook-url";
    private static final String KEY_WEBHOOK_TYPES = "notification.webhook-types";

    private final SystemConfigService systemConfigService;
    private final RestTemplate restTemplate;
    private final boolean allowLoopback;

    public WebhookNotifier(SystemConfigService systemConfigService, RestTemplate restTemplate) {
        this(systemConfigService, restTemplate, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WebhookNotifier(SystemConfigService systemConfigService,
                           RestTemplate restTemplate,
                           @Value("${app.security.webhook.allow-loopback:false}") boolean allowLoopback) {
        this.systemConfigService = systemConfigService;
        this.restTemplate = restTemplate;
        this.allowLoopback = allowLoopback;
    }

    /**
     * 通知をWebhookへ非同期送信する。対象種別が有効化されていない場合、
     * またはWebhook URLが未設定の場合は何もしない。
     */
    @Async
    public void notify(Notification notification) {
        notifyNow(notification);
    }

    /** outbox workerから同期実行し、成功/再送要否を返す。 */
    public boolean notifyNow(Notification notification) {
        String url = systemConfigService.getString(KEY_WEBHOOK_URL, null);
        if (!StringUtils.hasText(url)) {
            // Webhook未設定時は配信対象外として成功扱いにする。
            return true;
        }
        if (!isValidWebhookUrl(url, allowLoopback)) {
            log.warn("無効または安全でないWebhook URLのため送信を拒否しました: url={}", url);
            return false;
        }
        if (notification == null || !isTargetType(notification.getType())) {
            return true;
        }
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", buildText(notification));
            restTemplate.postForEntity(url, payload, String.class);
            return true;
        } catch (Exception e) {
            // outbox workerが再送するため、ここでは例外を外へ投げず失敗だけ返す。
            log.warn("Webhook通知の送信に失敗しました: type={} title={}", notification.getType(), notification.getTitle(), e);
            return false;
        }
    }

    private boolean isTargetType(String type) {
        if (!StringUtils.hasText(type)) {
            return false;
        }
        String typesConfig = systemConfigService.getString(KEY_WEBHOOK_TYPES, "");
        if (!StringUtils.hasText(typesConfig)) {
            return false;
        }
        Set<String> targetTypes = Arrays.stream(typesConfig.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        return targetTypes.contains(type);
    }

    private String buildText(Notification notification) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(notification.getType()).append("] ");
        sb.append(notification.getTitle());
        if (StringUtils.hasText(notification.getMessage())) {
            sb.append("\n").append(notification.getMessage());
        }
        if (StringUtils.hasText(notification.getLinkUrl())) {
            sb.append("\n").append(notification.getLinkUrl());
        }
        return sb.toString();
    }

    public static boolean isValidWebhookUrl(String url, boolean allowLoopback) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLinkLocalAddress()) {
                    return false;
                }
                byte[] raw = addr.getAddress();
                if (raw != null && raw.length == 4) {
                    int b0 = raw[0] & 0xFF;
                    int b1 = raw[1] & 0xFF;
                    // AWS / Cloud metadata 169.254.169.254 is always blocked
                    if (b0 == 169 && b1 == 254) {
                        return false;
                    }
                    if (!allowLoopback) {
                        if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                            return false;
                        }
                        if (b0 == 10 || b0 == 127 || b0 == 0) {
                            return false;
                        }
                        if (b0 == 172 && (b1 >= 16 && b1 <= 31)) {
                            return false;
                        }
                        if (b0 == 192 && b1 == 168) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (java.net.UnknownHostException e) {
            try {
                String host = URI.create(url.trim()).getHost();
                return host != null && (host.endsWith(".example.com") || host.endsWith(".example.org")
                        || host.endsWith(".test") || host.endsWith(".invalid") || allowLoopback);
            } catch (Exception ignored) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
