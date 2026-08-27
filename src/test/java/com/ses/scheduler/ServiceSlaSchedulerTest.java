package com.ses.scheduler;

import com.ses.dto.notification.NotificationDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.service.NotificationService;
import com.ses.service.scheduler.ServiceSlaScheduler;
import com.ses.service.servicedesk.ServiceRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceSlaSchedulerTest {

    @Autowired
    private ServiceSlaScheduler slaScheduler;

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private ServiceRequestMapper requestMapper;

    @Autowired
    private ServiceSlaClockMapper slaClockMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private NotificationService notificationService;

    private Long testCustomerId;
    private ServiceRequest testRequest;

    @BeforeEach
    void setUp() {
        Customer c = Customer.builder()
                .companyName("SLA監視テスト株式会社-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(c);
        testCustomerId = c.getId();

        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomerId)
                .category("CONTRACT")
                .priority("P0") // P0: 初回応答1時間, 解決4時間
                .subject("SLAテスト対象問い合わせ")
                .description("SLAスケジューラ検証用")
                .ownerUserId(1L)
                .build();
        testRequest = serviceRequestService.createRequest(req, 1L, false, null);
    }

    @Test
    @DisplayName("初回応答期限超過を検知してフラグ更新と超過通知が発行されること")
    void testResponseBreach_detectedAndNotified() {
        ServiceSlaClock clock = slaClockMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, testRequest.getId())
        );

        assertFalse(clock.getResponseBreached());

        // 初回応答期限を10分過ぎた時刻でスケジューラ実行
        LocalDateTime asOf = clock.getResponseDeadline().plusMinutes(10);
        int processed = slaScheduler.processSlaMonitoring(asOf);

        assertTrue(processed > 0);

        ServiceSlaClock updatedClock = slaClockMapper.selectById(clock.getId());
        assertTrue(updatedClock.getResponseBreached());

        // 通知が発行されていることを確認
        List<NotificationDto> notifications = notificationService.getRecentNotifications(1L);
        boolean hasBreachNotice = notifications.stream()
                .anyMatch(n -> "SERVICE_DESK_SLA_BREACH".equals(n.getType()) && n.getMessage().contains("初回応答期限"));
        assertTrue(hasBreachNotice, "初回応答期限超過通知が発行されていること");
    }

    @Test
    @DisplayName("解決期限超過を検知してフラグ更新と超過通知が発行されること")
    void testResolveBreach_detectedAndNotified() {
        ServiceSlaClock clock = slaClockMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, testRequest.getId())
        );

        // 対応中（IN_PROGRESS）に進める
        serviceRequestService.changeStatus(testRequest.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");

        // 解決期限を10分過ぎた時刻でスケジューラ実行
        LocalDateTime asOf = clock.getResolveDeadline().plusMinutes(10);
        slaScheduler.processSlaMonitoring(asOf);

        ServiceSlaClock updatedClock = slaClockMapper.selectById(clock.getId());
        assertTrue(updatedClock.getResolveBreached());

        List<NotificationDto> notifications = notificationService.getRecentNotifications(1L);
        boolean hasResolveNotice = notifications.stream()
                .anyMatch(n -> "SERVICE_DESK_SLA_BREACH".equals(n.getType()) && n.getMessage().contains("解決期限"));
        assertTrue(hasResolveNotice, "解決期限超過通知が発行されていること");
    }

    @Test
    @DisplayName("スケジューラが連続実行されても同一超過通知が重複発行されないこと (Dedupe 実証)")
    void testDeduplication_preventsDuplicateNotifications() {
        ServiceSlaClock clock = slaClockMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, testRequest.getId())
        );

        LocalDateTime asOf = clock.getResponseDeadline().plusMinutes(10);

        // 1回目の実行
        slaScheduler.processSlaMonitoring(asOf);
        List<NotificationDto> notificationsAfterFirst = notificationService.getRecentNotifications(1L);
        long breachNoticeCount1 = notificationsAfterFirst.stream()
                .filter(n -> "SERVICE_DESK_SLA_BREACH".equals(n.getType()) && n.getMessage().contains("初回応答期限"))
                .count();
        assertEquals(1, breachNoticeCount1, "1回目の実行で1件の通知");

        // 2回目の実行 (5分後)
        slaScheduler.processSlaMonitoring(asOf.plusMinutes(5));
        List<NotificationDto> notificationsAfterSecond = notificationService.getRecentNotifications(1L);
        long breachNoticeCount2 = notificationsAfterSecond.stream()
                .filter(n -> "SERVICE_DESK_SLA_BREACH".equals(n.getType()) && n.getMessage().contains("初回応答期限"))
                .count();
        assertEquals(1, breachNoticeCount2, "2回目の実行後も通知は1件のまま重複しないこと");
    }

    @Test
    @DisplayName("解決済み（RESOLVED）のリクエストはSLA超過判定されないこと")
    void testResolvedRequest_ignoredByMonitoring() {
        serviceRequestService.changeStatus(testRequest.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(testRequest.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("完了").build(),
                1L, "INTERNAL_USER", "管理者");

        ServiceSlaClock clock = slaClockMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, testRequest.getId())
        );

        // 期限を過ぎた日時で実行
        LocalDateTime asOf = clock.getResolveDeadline().plusHours(5);
        int processed = slaScheduler.processSlaMonitoring(asOf);

        assertEquals(0, processed, "完了したリクエストは監視対象外");
    }
}
