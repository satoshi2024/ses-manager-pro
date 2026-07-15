package com.ses.service.notification;

import com.ses.entity.Notification;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

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
@RequiredArgsConstructor
public class WebhookNotifier {

    private static final String KEY_WEBHOOK_URL = "notification.webhook-url";
    private static final String KEY_WEBHOOK_TYPES = "notification.webhook-types";

    private final SystemConfigService systemConfigService;
    private final RestTemplate restTemplate;

    /**
     * 通知をWebhookへ非同期送信する。対象種別が有効化されていない場合、
     * またはWebhook URLが未設定の場合は何もしない。
     */
    @Async
    public void notify(Notification notification) {
        String url = systemConfigService.getString(KEY_WEBHOOK_URL, null);
        if (!StringUtils.hasText(url)) {
            // Webhook未設定時はアプリ全体の動作に影響を与えないようスキップする
            return;
        }
        if (notification == null || !isTargetType(notification.getType())) {
            return;
        }
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("text", buildText(notification));
            restTemplate.postForEntity(url, payload, String.class);
        } catch (Exception e) {
            // タイムアウト・4xx/5xx等の失敗は通知自体の生成・画面表示に影響させないため、
            // ログ出力のみとし再試行は行わない
            log.warn("Webhook通知の送信に失敗しました: type={} title={}", notification.getType(), notification.getTitle(), e);
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
}
