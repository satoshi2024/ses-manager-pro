package com.ses.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import java.util.List;

public interface NotificationService {
    List<NotificationDto> getRecentNotifications(Long userId);
    Page<NotificationDto> pageForUser(Long userId, long current, long size, String type, Boolean unreadOnly);
    long unreadCount(Long userId);
    void markRead(Long notificationId, Long userId);
    void markAllRead(Long userId);
    void publish(String type, String title, String message, String linkUrl, String dedupeKey);
    default void publish(String type, String title, String message, String linkUrl, String dedupeKey, String menuKey) {
        publish(type, title, message, linkUrl, dedupeKey);
    }
    void publishToUser(Long userId, String type, String title, String message, String linkUrl, String dedupeKey);
    default void publishToUser(Long userId, String type, String title, String message, String linkUrl, String dedupeKey, String menuKey) {
        publishToUser(userId, type, title, message, linkUrl, dedupeKey);
    }

    /**
     * 通知outboxへ登録したIDを返すreport等の監査連携用経路。
     * 既存通知実装との互換性のため、未対応実装はnullを返す。
     */
    default Long publishToUserAndGetOutboxId(Long userId, String type, String title, String message,
                                               String linkUrl, String dedupeKey, String menuKey) {
        publishToUser(userId, type, title, message, linkUrl, dedupeKey, menuKey);
        return null;
    }

    /** 組織固有の全体通知。organizationId=nullはプラットフォーム共通通知に限定する。 */
    void publishToOrganization(Long organizationId, String type, String title, String message,
                               String linkUrl, String dedupeKey);
}
