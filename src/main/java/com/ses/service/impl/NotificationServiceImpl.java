package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import com.ses.entity.NotificationRead;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationReadMapper;
import com.ses.service.NotificationService;
import com.ses.service.notification.WebhookNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationReadMapper notificationReadMapper;
    private final WebhookNotifier webhookNotifier;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<NotificationDto> getRecentNotifications(Long userId) {
        List<NotificationDto> list = notificationMapper.selectPageForUser(userId, null, null, 10, 0);
        list.forEach(this::translateDto);
        return list;
    }

    @Override
    public Page<NotificationDto> pageForUser(Long userId, long current, long size, String type, Boolean unreadOnly) {
        Page<NotificationDto> page = new Page<>(current, size);
        int offset = (int) ((current - 1) * size);
        List<NotificationDto> records = notificationMapper.selectPageForUser(userId, type, unreadOnly, (int) size, offset);
        records.forEach(this::translateDto);
        long total = notificationMapper.countPageForUser(userId, type, unreadOnly);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    private void translateDto(NotificationDto dto) {
        if (dto.getMessage() != null && dto.getMessage().startsWith("[")) {
            try {
                List<String> parts = objectMapper.readValue(dto.getMessage(), new TypeReference<List<String>>(){});
                if (!parts.isEmpty()) {
                    String key = parts.get(0);
                    Object[] args = parts.subList(1, parts.size()).toArray();
                    String translated = messageSource.getMessage(key, args, dto.getMessage(), LocaleContextHolder.getLocale());
                    dto.setMessage(translated);
                }
            } catch (Exception e) {
                // Not JSON or missing key, ignore and leave as is
            }
        }
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationMapper.countUnread(userId);
    }

    @Override
    public void markRead(Long notificationId, Long userId) {
        if (notificationMapper.countVisible(notificationId, userId) == 0) return;
        try {
            NotificationRead read = new NotificationRead();
            read.setNotificationId(notificationId);
            read.setUserId(userId);
            read.setReadAt(LocalDateTime.now());
            notificationReadMapper.insert(read);
        } catch (DuplicateKeyException e) {
            // insert ignore equivalent
        }
    }

    @Override
    public void markAllRead(Long userId) {
        notificationMapper.markAllReadForUser(userId);
    }

    @Override
    public void publish(String type, String title, String message, String linkUrl, String dedupeKey) {
        publishInternal(type, title, message, linkUrl, dedupeKey, menuKeyForType(type));
    }

    private String menuKeyForType(String type) {
        if (type == null) return null;
        return switch (type) {
            case "INVOICE_OVERDUE" -> "invoice";
            case "CONTRACT_END", "CONTRACT_DRAFT" -> "contract";
            case "BENCH_LONG" -> "engineer";
            case "PROJECT_URGENT" -> "project";
            case "PROPOSAL_STALE" -> "proposal";
            case "FOLLOW_UP" -> "customer";
            default -> null;
        };
    }

    @Override
    public void publish(String type, String title, String message, String linkUrl, String dedupeKey, String menuKey) {
        publishInternal(type, title, message, linkUrl, dedupeKey, menuKey);
    }

    private void publishInternal(String type, String title, String message, String linkUrl, String dedupeKey, String menuKey) {
        try {
            Notification notification = new Notification();
            notification.setType(type);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setLinkUrl(linkUrl);
            notification.setMenuKey(menuKey);
            notification.setDedupeKey(dedupeKey);
            notification.setCreatedAt(LocalDateTime.now());
            notificationMapper.insert(notification);
            webhookNotifier.notify(notification);
        } catch (DuplicateKeyException e) {
            // idempotent
        }
    }
}
