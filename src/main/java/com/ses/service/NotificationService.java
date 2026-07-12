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
}
