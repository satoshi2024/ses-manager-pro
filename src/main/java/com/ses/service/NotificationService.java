package com.ses.service;

import com.ses.dto.notification.NotificationDto;
import java.util.List;

public interface NotificationService {
    List<NotificationDto> getRecentNotifications();
}
