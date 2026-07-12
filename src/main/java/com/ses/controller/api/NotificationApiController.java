package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.dto.notification.NotificationDto;
import com.ses.entity.SysUser;
import com.ses.service.NotificationGenerateService;
import com.ses.service.NotificationService;
import com.ses.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApiController {

    private final NotificationService notificationService;
    private final NotificationGenerateService notificationGenerateService;
    private final SysUserService sysUserService;

    @GetMapping
    public ApiResult<List<NotificationDto>> getNotifications(Authentication authentication) {
        Long userId = getCurrentUserId(authentication);
        if (userId == null) return ApiResult.success(List.of());
        List<NotificationDto> notifications = notificationService.getRecentNotifications(userId);
        return ApiResult.success(notifications);
    }

    @GetMapping("/page")
    public ApiResult<Page<NotificationDto>> getNotificationsPage(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean unreadOnly,
            Authentication authentication) {
        Long userId = getCurrentUserId(authentication);
        if (userId == null) return ApiResult.error(401, "認証されていません");
        return ApiResult.success(notificationService.pageForUser(userId, current, size, type, unreadOnly));
    }

    @GetMapping("/unread-count")
    public ApiResult<Long> getUnreadCount(Authentication authentication) {
        Long userId = getCurrentUserId(authentication);
        if (userId == null) return ApiResult.success(0L);
        return ApiResult.success(notificationService.unreadCount(userId));
    }

    @PutMapping("/{id}/read")
    public ApiResult<Void> markRead(@PathVariable Long id, Authentication authentication) {
        Long userId = getCurrentUserId(authentication);
        if (userId != null) {
            notificationService.markRead(id, userId);
        }
        return ApiResult.success(null);
    }

    @PutMapping("/read-all")
    public ApiResult<Void> markAllRead(Authentication authentication) {
        Long userId = getCurrentUserId(authentication);
        if (userId != null) {
            notificationService.markAllRead(userId);
        }
        return ApiResult.success(null);
    }

    @PostMapping("/generate")
    public ApiResult<Void> generateNotifications() {
        notificationGenerateService.generateAll();
        return ApiResult.success(null);
    }

    private Long getCurrentUserId(Authentication authentication) {
        Authentication auth = authentication != null ? authentication : org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        SysUser sysUser = sysUserService.getOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, auth.getName()));
        return sysUser != null ? sysUser.getId() : null;
    }
}
