package com.ses.service.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaEscalationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceSlaSchedulerTest {

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Autowired
    private ServiceSlaScheduler serviceSlaScheduler;

    @Autowired
    private ServiceSlaMonitoringService monitoringService;

    @Autowired
    private com.ses.service.servicedesk.impl.ServiceSlaMonitoringServiceImpl monitoringServiceImpl;

    @Autowired
    private ServiceRequestMapper requestMapper;

    @Autowired
    private ServiceSlaClockMapper clockMapper;

    @Autowired
    private ServiceSlaEscalationMapper escalationMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerSalesMapper engineerSalesMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("SLA超過検知時、①リクエストOwner宛てに通知され、重複実行時は通知が重複送信されないこと")
    void testBreachDetectionAndNotificationDeduplication_ownerRecipient() {
        // 1. 顧客とOwnerユーザー作成
        Customer customer = Customer.builder().companyName("テスト顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);

        SysUser owner = new SysUser();
        owner.setUsername("owner_" + UUID.randomUUID());
        owner.setPassword("pass123");
        owner.setRealName("Owner User");
        owner.setRole("営業");
        owner.setStatus(1);
        userMapper.insert(owner);

        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId())
                .status("IN_PROGRESS")
                .priority("P0")
                .category("SYSTEM")
                .channel("INTERNAL")
                .subject("重大障害")
                .description("障害詳細テスト")
                .ownerUserId(owner.getId())
                .build();
        requestMapper.insert(req);

        // 過去の日時で超過しているSLAクロックを作成 (responseBreached = false, resolveBreached = false)
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId())
                .roundNo(1)
                .policyId(1L)
                .status("RUNNING")
                .responseDeadline(LocalDateTime.now().minusHours(2))
                .resolveDeadline(LocalDateTime.now().minusHours(1))
                .responseBreached(false)
                .resolveBreached(false)
                .build();
        clockMapper.insert(clock);

        // 1回目のスケジューラ実行
        serviceSlaScheduler.monitorSlaClocks();

        // Owner 宛てに通知が送信されたことを検証
        verify(notificationService, atLeastOnce()).publishToUser(eq(owner.getId()), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

        // DBの超過フラグがtrueに更新されていること
        ServiceSlaClock updatedClock = clockMapper.selectById(clock.getId());
        assertTrue(Boolean.TRUE.equals(updatedClock.getResponseBreached()));
        assertTrue(Boolean.TRUE.equals(updatedClock.getResolveBreached()));

        // 2回目のスケジューラ実行（重複チェック）
        serviceSlaScheduler.monitorSlaClocks();

        // 重複通知は送信されないこと（呼び出し回数が増えていないこと）
        verify(notificationService, times(2)).publishToUser(eq(owner.getId()), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Ownerが空の場合、②契約担当営業に通知がフォールバックされること")
    void testEscalation_fallbackToContractSales() {
        Customer customer = Customer.builder().companyName("テスト顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("Test Project " + UUID.randomUUID());
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        Engineer engineer = new Engineer();
        engineer.setTenantId("default");
        engineer.setFullName("Test Engineer " + UUID.randomUUID());
        engineer.setEmploymentType("正社員");
        engineerMapper.insert(engineer);

        SysUser salesUser = new SysUser();
        salesUser.setTenantId("default");
        salesUser.setUsername("sales_" + UUID.randomUUID());
        salesUser.setPassword("pass123");
        salesUser.setRealName("Sales Rep");
        salesUser.setRole("営業");
        salesUser.setStatus(1);
        userMapper.insert(salesUser);

        Contract contract = new Contract();
        contract.setContractNo("CT-" + UUID.randomUUID());
        contract.setCustomerId(customer.getId());
        contract.setProjectId(project.getId());
        contract.setEngineerId(engineer.getId());
        contract.setSalesUserId(salesUser.getId()); // 契約担当営業
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setStartDate(LocalDate.now());
        contract.setEndDate(LocalDate.now().plusMonths(3));
        contract.setStatus("稼動中");
        contract.setTenantId("default");
        contractMapper.insert(contract);

        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId())
                .contractId(contract.getId())
                .status("RECEIVED")
                .priority("P1")
                .category("CONTRACT")
                .channel("INTERNAL")
                .subject("契約問い合わせ")
                .description("契約詳細テスト")
                .ownerUserId(null) // Ownerなし
                .build();
        requestMapper.insert(req);

        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId())
                .roundNo(1)
                .policyId(1L)
                .status("RUNNING")
                .responseDeadline(LocalDateTime.now().minusHours(1))
                .resolveDeadline(LocalDateTime.now().plusHours(4))
                .responseBreached(false)
                .resolveBreached(false)
                .build();
        clockMapper.insert(clock);

        serviceSlaScheduler.monitorSlaClocks();

        // 契約担当営業に通知されたこと
        verify(notificationService, times(1)).publishToUser(eq(salesUser.getId()), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Owner・契約営業・顧客主営業が全て空の場合、④アクティブな管理者全員にエスカレーション通知されること（ID 1への硬直フォールバックなし）")
    void testEscalation_fallbackToActiveAdmins() {
        Customer customer = Customer.builder().companyName("テスト顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);

        // 管理者ユーザーを作成 (ID = 888L, 889L)
        SysUser admin1 = new SysUser();
        admin1.setUsername("admin_test_1_" + UUID.randomUUID());
        admin1.setPassword("pass123");
        admin1.setRealName("Admin 1");
        admin1.setRole("管理者");
        admin1.setStatus(1);
        userMapper.insert(admin1);

        SysUser admin2 = new SysUser();
        admin2.setUsername("admin_test_2_" + UUID.randomUUID());
        admin2.setPassword("pass123");
        admin2.setRealName("Admin 2");
        admin2.setRole("管理者");
        admin2.setStatus(1);
        userMapper.insert(admin2);

        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId())
                .status("RECEIVED")
                .priority("P0")
                .category("SYSTEM")
                .channel("INTERNAL")
                .subject("緊急障害")
                .description("緊急障害詳細テスト")
                .ownerUserId(null)
                .contractId(null)
                .build();
        requestMapper.insert(req);

        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId())
                .roundNo(1)
                .policyId(1L)
                .status("RUNNING")
                .responseDeadline(LocalDateTime.now().minusMinutes(30))
                .resolveDeadline(LocalDateTime.now().plusHours(2))
                .responseBreached(false)
                .resolveBreached(false)
                .build();
        clockMapper.insert(clock);

        serviceSlaScheduler.monitorSlaClocks();

        // admin1 および admin2 宛てに通知が送信されたこと
        verify(notificationService, atLeastOnce()).publishToUser(eq(admin1.getId()), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(notificationService, atLeastOnce()).publishToUser(eq(admin2.getId()), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("不適格recipientしかいないSLAは件名を通知せずrecipientCount=0でretry台帳へ残す")
    void noCurrentlyEligibleRecipientDoesNotPublishNotification() {
        String tenantId = "nf02-no-recipient-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenantId);
        Customer customer = Customer.builder().companyName("recipient-scope-" + UUID.randomUUID()).build();
        customer.setTenantId(tenantId);
        customerMapper.insert(customer);

        SysUser hr = new SysUser();
        hr.setTenantId(tenantId);
        hr.setUsername("hr_recipient_" + UUID.randomUUID());
        hr.setPassword("pass123");
        hr.setRealName("不適格HR");
        hr.setRole("HR");
        hr.setStatus(1);
        userMapper.insert(hr);

        String subject = "機密件名-RECIPIENT-SCOPE-" + UUID.randomUUID();
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .tenantId(tenantId).customerId(customer.getId()).ownerUserId(hr.getId())
                .status("IN_PROGRESS").priority("P1").category("QUALITY").channel("INTERNAL")
                .subject(subject).description("private detail").build();
        requestMapper.insert(req);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(LocalDateTime.now().minusMinutes(2))
                .resolveDeadline(LocalDateTime.now().plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        monitoringService.checkSlaBreaches(LocalDateTime.now());

        // 同じcontextの他機能（例: 時間外コンプライアンスの@Async通知）がmockへ届いても誤判定しないよう、SLA通知種別に限定する
        verify(notificationService, never()).publishToUser(any(), startsWith("SLA_"), anyString(), anyString(), anyString(), anyString(), anyString());
        String dedupeKey = "SLA_FIRST:" + req.getId() + ":1:RESPONSE:FIRST";
        var escalation = escalationMapper.selectByDedupeKeyAndTenant(dedupeKey, tenantId);
        assertNotNull(escalation);
        assertEquals(0, escalation.getRecipientCount());
        assertEquals("RETRY", escalation.getStatus());
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_outbox WHERE dedupe_key=?", Integer.class, dedupeKey);
        assertEquals(0, outboxRows);
    }

    @Test
    @DisplayName("期限30分前warningと初回breach・継続breachが別段階で記録されること")
    void testWarningFirstAndContinuingBreach() {
        Customer customer = Customer.builder().companyName("warning顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);
        SysUser owner = activeUser("warning_owner");

        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("warning test").description("warning")
                .ownerUserId(owner.getId()).build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.plusMinutes(20)).resolveDeadline(base.plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        monitoringService.checkSlaBreaches(base);
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        ServiceSlaClock warningClock = clockMapper.selectById(clock.getId());
        assertTrue(Boolean.TRUE.equals(warningClock.getResponseWarningSent()));

        // 期限超過直後の初回通知
        monitoringService.checkSlaBreaches(base.plusMinutes(21));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());

        // 30分経過後は継続通知を1回だけ送る
        monitoringService.checkSlaBreaches(base.plusMinutes(52));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH_CONTINUING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(3, escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())));
    }

    @Test
    @DisplayName("初回応答を記録済みの依頼には初回応答期限のwarningを出さず、解決期限のwarningは従来どおり出すこと")
    void respondedRequestDoesNotReceiveResponseDeadlineWarning() {
        Customer customer = Customer.builder().companyName("応答済warning顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);
        // username = prefix + "_" + UUID(36文字) は sys_user.username VARCHAR(50) に収める（prefixは13文字以下）
        SysUser owner = activeUser("resp_owner");

        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("responded warning test").description("responded")
                .ownerUserId(owner.getId()).build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        // 「対応着手」で初回応答を記録済み（ServiceRequestServiceImplのIN_PROGRESS遷移と同じ状態）
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.plusMinutes(20)).resolveDeadline(base.plusHours(2))
                .firstRespondedAt(base.minusMinutes(5))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        // 初回応答期限まで20分（warningの時間帯）でも、応答済みなのでwarningを出さない
        monitoringService.checkSlaBreaches(base);
        verify(notificationService, never()).publishToUser(eq(owner.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertFalse(Boolean.TRUE.equals(clockMapper.selectById(clock.getId()).getResponseWarningSent()));

        // 初回応答期限を過ぎても、応答済みなので超過通知も出さない（既存の超過判定と同じ扱い）
        monitoringService.checkSlaBreaches(base.plusMinutes(21));
        verify(notificationService, never()).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());

        // 解決期限まで25分になれば、解決期限のwarningだけを1回出す
        monitoringService.checkSlaBreaches(base.plusMinutes(95));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        ServiceSlaClock resolvedWindow = clockMapper.selectById(clock.getId());
        assertTrue(Boolean.TRUE.equals(resolvedWindow.getResolveWarningSent()));
        assertFalse(Boolean.TRUE.equals(resolvedWindow.getResponseWarningSent()));
        assertEquals(1, escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())));
    }

    @Test
    @DisplayName("受信者なしescalationを永続化し、受信者出現後に同一dedupeで再送して重複しないこと")
    void testNoRecipientEscalationIsPersistedAndRetriedIdempotently() {
        userMapper.selectList(null).forEach(user -> {
            user.setStatus(0);
            userMapper.updateById(user);
        });

        Customer customer = Customer.builder().companyName("recipientなし顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("recipient retry").description("retry")
                .build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.minusMinutes(1)).resolveDeadline(base.plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        monitoringService.checkSlaBreaches(base);
        assertEquals(0, escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())
                .eq(com.ses.entity.ServiceSlaEscalation::getStatus, "SENT")));
        assertEquals(1, escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())
                .eq(com.ses.entity.ServiceSlaEscalation::getStatus, "RETRY")));
        // 同じcontextの他機能（例: 時間外コンプライアンスの@Async通知）がmockへ届いても誤判定しないよう、SLA通知種別に限定する
        verify(notificationService, never()).publishToUser(any(), startsWith("SLA_"), anyString(), anyString(), anyString(), anyString(), anyString());

        SysUser owner = activeUser("retry_owner");
        req.setOwnerUserId(owner.getId());
        requestMapper.updateById(req);

        monitoringService.checkSlaBreaches(base.plusMinutes(6));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        monitoringService.checkSlaBreaches(base.plusMinutes(7));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(1, escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())
                .eq(com.ses.entity.ServiceSlaEscalation::getStatus, "SENT")));
    }

    @Test
    @DisplayName("SLA通知の契約候補は同一顧客でもcurrent tenantの契約だけを使う")
    void contractRecipientDoesNotCrossTenant() {
        Customer customer = Customer.builder().companyName("契約tenant分離顧客-" + UUID.randomUUID())
                .tenantId("default").build();
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("契約tenant分離案件-" + UUID.randomUUID());
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);
        Engineer engineer = new Engineer();
        engineer.setFullName("契約tenant分離要員-" + UUID.randomUUID());
        engineer.setEmploymentType("正社員");
        engineerMapper.insert(engineer);

        SysUser salesA = activeSales("sla-a", "default");
        SysUser salesB = activeSales("sla-b", "tenant-b");
        Contract contractA = contract(customer.getId(), project.getId(), engineer.getId(), salesA.getId(), "default");
        Contract contractB = contract(customer.getId(), project.getId(), engineer.getId(), salesB.getId(), "tenant-b");
        contractMapper.insert(contractA);
        contractMapper.insert(contractB);

        ServiceRequest request = ServiceRequest.builder().requestNo("REQ-" + UUID.randomUUID())
                .tenantId("default").customerId(customer.getId()).contractId(contractB.getId())
                .status("RECEIVED").build();
        assertTrue(monitoringServiceImpl.resolveNotificationRecipients(request).isEmpty());
        request.setTenantId(null);
        assertTrue(monitoringServiceImpl.resolveNotificationRecipients(request).isEmpty());
    }

    // ---------------------------------------------------------------- HYG1 P3-5: warning再送の冪等性

    @Test
    @DisplayName("宛先不在でRETRYになった初回応答warningは初回応答の記録後に再送せずCANCELLEDで閉じる")
    void responseWarningRetryIsCancelledAfterFirstResponse() {
        WarningRetryFixture fixture = responseWarningRetryWithoutRecipient();
        // 「対応着手」で初回応答を記録し、宛先になれる管理者を用意する
        clockMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceSlaClock>()
                .eq(ServiceSlaClock::getId, fixture.clock().getId())
                .set(ServiceSlaClock::getFirstRespondedAt, fixture.base().plusMinutes(3)));
        SysUser admin = activeAdmin("warn_admin", fixture.tenantId());

        // 再送時刻（+5分）を過ぎても、応答済みなので初回応答warningの再送を出さない
        monitoringService.checkSlaBreaches(fixture.base().plusMinutes(6));
        verify(notificationService, never()).publishToUser(eq(admin.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        com.ses.entity.ServiceSlaEscalation closed =
                escalationMapper.selectByDedupeKeyAndTenant(fixture.dedupeKey(), fixture.tenantId());
        assertEquals("CANCELLED", closed.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(closed.getNextRetryAt());

        // 同じ管理者は有効な宛先である（解決期限のwarningは1回だけ届く）。上のnever()が空の断言でないことの確認
        monitoringService.checkSlaBreaches(fixture.base().plusMinutes(95));
        verify(notificationService, times(1)).publishToUser(eq(admin.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals("CANCELLED", escalationMapper.selectByDedupeKeyAndTenant(
                fixture.dedupeKey(), fixture.tenantId()).getStatus());
    }

    @Test
    @DisplayName("宛先不在でRETRYになった初回応答warningは期限超過後は再送せず超過通知だけを送る")
    void responseWarningRetryIsSupersededByBreach() {
        WarningRetryFixture fixture = responseWarningRetryWithoutRecipient();
        SysUser admin = activeAdmin("breach_admin", fixture.tenantId());

        // 未応答のまま初回応答期限（+20分）を過ぎた: 超過の初回通知だけを送り、期限前の予告は送らない
        monitoringService.checkSlaBreaches(fixture.base().plusMinutes(21));
        verify(notificationService, times(1)).publishToUser(eq(admin.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        verify(notificationService, never()).publishToUser(eq(admin.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals("CANCELLED", escalationMapper.selectByDedupeKeyAndTenant(
                fixture.dedupeKey(), fixture.tenantId()).getStatus());
    }

    @Test
    @DisplayName("未応答で期限前の初回応答warningのRETRYは従来どおり宛先の出現後に1回だけ再送する")
    void pendingResponseWarningRetryIsStillResentOnce() {
        WarningRetryFixture fixture = responseWarningRetryWithoutRecipient();
        SysUser admin = activeAdmin("resend_admin", fixture.tenantId());

        monitoringService.checkSlaBreaches(fixture.base().plusMinutes(6));
        monitoringService.checkSlaBreaches(fixture.base().plusMinutes(12));
        verify(notificationService, times(1)).publishToUser(eq(admin.getId()), eq("SLA_WARNING"),
                anyString(), anyString(), anyString(), anyString(), eq("service-desk"));
        assertEquals("SENT", escalationMapper.selectByDedupeKeyAndTenant(
                fixture.dedupeKey(), fixture.tenantId()).getStatus());
    }

    // ---------------------------------------------------------------- RB-NF02 review P3-4: 応答後の超過継続

    @Test
    @DisplayName("初回応答期限の超過後に初回応答した依頼には初回応答の超過継続を送らず解決目標の超過継続は従来どおり送る")
    void respondedAfterBreachStopsResponseContinuingButKeepsResolveContinuing() {
        Customer customer = Customer.builder().companyName("超過後応答顧客-" + UUID.randomUUID()).build();
        customer.setTenantId("default");
        customerMapper.insert(customer);
        SysUser owner = activeUser("cont_owner");
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("continuing test").description("continuing")
                .ownerUserId(owner.getId()).build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.plusMinutes(20)).resolveDeadline(base.plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        // 初回応答期限の超過（初回通知）
        monitoringService.checkSlaBreaches(base.plusMinutes(21));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        // 超過の後に「対応着手」で初回応答を記録した
        recordFirstResponse(clock, base.plusMinutes(25));

        // 30分経過しても、応答済みなので初回応答の超過継続は送らない（修正前はここで1回送っていた）
        monitoringService.checkSlaBreaches(base.plusMinutes(52));
        verify(notificationService, never()).publishToUser(eq(owner.getId()), eq("SLA_BREACH_CONTINUING"),
                anyString(), anyString(), anyString(), anyString(), anyString());

        // 解決目標の超過と超過継続は従来どおり（同じownerへ届く。上のnever()が空の断言でないことの確認）
        monitoringService.checkSlaBreaches(base.plusMinutes(121));
        verify(notificationService, times(2)).publishToUser(eq(owner.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        monitoringService.checkSlaBreaches(base.plusMinutes(152));
        verify(notificationService, times(1)).publishToUser(eq(owner.getId()), eq("SLA_BREACH_CONTINUING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(0, countEscalations(req.getId(), "RESPONSE", "CONTINUING"));
        assertEquals(1, countEscalations(req.getId(), "RESOLVE", "CONTINUING"));
    }

    @Test
    @DisplayName("宛先不在でRETRYになった初回応答の超過継続は超過後の初回応答の記録後に再送せずCANCELLEDで閉じる")
    void responseContinuingRetryIsCancelledAfterFirstResponse() {
        String tenantId = "nf02-continuing-retry-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenantId);
        Customer customer = Customer.builder().companyName("継続再送顧客-" + UUID.randomUUID()).build();
        customer.setTenantId(tenantId);
        customerMapper.insert(customer);
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .tenantId(tenantId).customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("continuing retry").description("retry").build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.plusMinutes(20)).resolveDeadline(base.plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);
        SysUser admin = activeAdmin("cont_admin", tenantId);

        // 超過の初回通知は管理者へ届く
        monitoringService.checkSlaBreaches(base.plusMinutes(21));
        verify(notificationService, times(1)).publishToUser(eq(admin.getId()), eq("SLA_BREACH"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        // 超過継続の時点では宛先がいない（管理者を一時的に無効化）→ RETRY台帳になる
        admin.setStatus(0);
        userMapper.updateById(admin);
        monitoringService.checkSlaBreaches(base.plusMinutes(52));
        com.ses.entity.ServiceSlaEscalation pending = escalationMapper.selectOne(
                new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                        .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, req.getId())
                        .eq(com.ses.entity.ServiceSlaEscalation::getBreachType, "RESPONSE")
                        .eq(com.ses.entity.ServiceSlaEscalation::getStage, "CONTINUING"));
        assertNotNull(pending);
        assertEquals("RETRY", pending.getStatus());

        // 初回応答を記録し、宛先を戻す。再送時刻（+5分）を過ぎても超過継続の再送は出さず台帳を閉じる
        recordFirstResponse(clock, base.plusMinutes(55));
        admin.setStatus(1);
        userMapper.updateById(admin);
        monitoringService.checkSlaBreaches(base.plusMinutes(58));
        verify(notificationService, never()).publishToUser(eq(admin.getId()), eq("SLA_BREACH_CONTINUING"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals("CANCELLED", escalationMapper.selectById(pending.getId()).getStatus());
    }

    private void recordFirstResponse(ServiceSlaClock clock, LocalDateTime respondedAt) {
        clockMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceSlaClock>()
                .eq(ServiceSlaClock::getId, clock.getId())
                .set(ServiceSlaClock::getFirstRespondedAt, respondedAt));
    }

    private long countEscalations(Long requestId, String breachType, String stage) {
        return escalationMapper.selectCount(new LambdaQueryWrapper<com.ses.entity.ServiceSlaEscalation>()
                .eq(com.ses.entity.ServiceSlaEscalation::getServiceRequestId, requestId)
                .eq(com.ses.entity.ServiceSlaEscalation::getBreachType, breachType)
                .eq(com.ses.entity.ServiceSlaEscalation::getStage, stage));
    }

    private record WarningRetryFixture(String tenantId, ServiceSlaClock clock, String dedupeKey, LocalDateTime base) {
    }

    /**
     * 専用tenant（管理者なし＝宛先不在）で、初回応答期限まで20分の問い合わせを1回監視し、
     * 初回応答warningをRETRY台帳（recipientCount=0）にした状態を作る。
     */
    private WarningRetryFixture responseWarningRetryWithoutRecipient() {
        String tenantId = "nf02-warning-retry-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenantId);
        Customer customer = Customer.builder().companyName("warning再送顧客-" + UUID.randomUUID()).build();
        customer.setTenantId(tenantId);
        customerMapper.insert(customer);
        ServiceRequest req = ServiceRequest.builder()
                .requestNo("REQ-" + UUID.randomUUID().toString().substring(0, 8))
                .tenantId(tenantId).customerId(customer.getId()).status("IN_PROGRESS").priority("P0")
                .category("SYSTEM").channel("INTERNAL").subject("warning retry").description("retry").build();
        requestMapper.insert(req);
        LocalDateTime base = LocalDateTime.of(2026, 8, 31, 10, 0);
        ServiceSlaClock clock = ServiceSlaClock.builder()
                .serviceRequestId(req.getId()).roundNo(1).policyId(1L).status("RUNNING")
                .responseDeadline(base.plusMinutes(20)).resolveDeadline(base.plusHours(2))
                .responseBreached(false).resolveBreached(false).build();
        clockMapper.insert(clock);

        monitoringService.checkSlaBreaches(base);

        String dedupeKey = "SLA_WARNING:" + req.getId() + ":1:RESPONSE:WARNING";
        com.ses.entity.ServiceSlaEscalation pending = escalationMapper.selectByDedupeKeyAndTenant(dedupeKey, tenantId);
        assertNotNull(pending);
        assertEquals("RETRY", pending.getStatus());
        assertEquals(0, pending.getRecipientCount());
        verify(notificationService, never()).publishToUser(any(), startsWith("SLA_"),
                anyString(), anyString(), anyString(), anyString(), anyString());
        return new WarningRetryFixture(tenantId, clock, dedupeKey, base);
    }

    private SysUser activeAdmin(String prefix, String tenantId) {
        // username = prefix + "_" + UUID(36文字) は sys_user.username VARCHAR(50) に収める（prefixは13文字以下）
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(prefix + "_" + UUID.randomUUID());
        user.setPassword("pass123");
        user.setRealName(prefix);
        user.setRole("管理者");
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private SysUser activeUser(String prefix) {
        SysUser user = new SysUser();
        user.setUsername(prefix + "_" + UUID.randomUUID());
        user.setPassword("pass123");
        user.setRealName(prefix);
        user.setRole("営業");
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private SysUser activeSales(String prefix, String tenantId) {
        SysUser user = activeUser(prefix);
        user.setTenantId(tenantId);
        userMapper.updateById(user);
        return userMapper.selectById(user.getId());
    }

    private Contract contract(Long customerId, Long projectId, Long engineerId, Long salesUserId, String tenantId) {
        Contract contract = new Contract();
        contract.setContractNo("CT-" + UUID.randomUUID());
        contract.setCustomerId(customerId);
        contract.setProjectId(projectId);
        contract.setEngineerId(engineerId);
        contract.setSalesUserId(salesUserId);
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setStartDate(LocalDate.now());
        contract.setEndDate(LocalDate.now().plusMonths(3));
        contract.setStatus("稼動中");
        contract.setTenantId(tenantId);
        return contract;
    }
}
