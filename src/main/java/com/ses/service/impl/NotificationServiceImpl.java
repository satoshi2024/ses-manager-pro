package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.Notification;
import com.ses.entity.NotificationRead;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationReadMapper;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationMapper notificationMapper;
    private final NotificationReadMapper notificationReadMapper;

    @Override
    public List<NotificationDto> getRecentNotifications(Long userId) {
        return notificationMapper.selectPageForUser(userId, null, null, 10, 0);
    }

    @Override
    public Page<NotificationDto> pageForUser(Long userId, long current, long size, String type, Boolean unreadOnly) {
        Page<NotificationDto> page = new Page<>(current, size);
        int offset = (int) ((current - 1) * size);
        List<NotificationDto> records = notificationMapper.selectPageForUser(userId, type, unreadOnly, (int) size, offset);
        long total = notificationMapper.countPageForUser(userId, type, unreadOnly);
        page.setRecords(records);
        page.setTotal(total);
        return page;
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationMapper.countUnread(userId);
    }

    @Override
    public void markRead(Long notificationId, Long userId) {
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
        List<NotificationDto> unreadList = notificationMapper.selectPageForUser(userId, null, true, 1000, 0);
        for (NotificationDto dto : unreadList) {
            if (dto.getIsRead() == null || !dto.getIsRead()) {
                markRead(dto.getId(), userId);
            }
        }
    }

    @Override
    public void publish(String type, String title, String message, String linkUrl, String dedupeKey) {
        try {
            Notification notification = new Notification();
            notification.setType(type);
            notification.setTitle(title);
            notification.setMessage(message);
            notification.setLinkUrl(linkUrl);
            notification.setDedupeKey(dedupeKey);
            notification.setCreatedAt(LocalDateTime.now());
            notificationMapper.insert(notification);
        } catch (DuplicateKeyException e) {
            // idempotent
        }
    }
}
