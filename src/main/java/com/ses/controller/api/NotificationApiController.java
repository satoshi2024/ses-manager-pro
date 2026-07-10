package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.notification.NotificationDto;
import com.ses.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApiController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResult<List<NotificationDto>> getNotifications() {
        List<NotificationDto> notifications = notificationService.getRecentNotifications();
        return ApiResult.success(notifications);
    }
}
