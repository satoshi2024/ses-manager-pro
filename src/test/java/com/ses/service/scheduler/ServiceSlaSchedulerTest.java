package com.ses.service.scheduler;

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
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.servicedesk.ServiceSlaMonitoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ServiceSlaSchedulerTest {

    @Autowired
    private ServiceSlaScheduler serviceSlaScheduler;

    @Autowired
    private ServiceSlaMonitoringService monitoringService;

    @Autowired
    private ServiceRequestMapper requestMapper;

    @Autowired
    private ServiceSlaClockMapper clockMapper;

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

    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("SLA超過検知時、①リクエストOwner宛てに通知され、重複実行時は通知が重複送信されないこと")
    void testBreachDetectionAndNotificationDeduplication_ownerRecipient() {
        // 1. 顧客とOwnerユーザー作成
        Customer customer = Customer.builder().companyName("テスト顧客-" + UUID.randomUUID()).build();
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
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("Test Project " + UUID.randomUUID());
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        Engineer engineer = new Engineer();
        engineer.setFullName("Test Engineer " + UUID.randomUUID());
        engineer.setEmploymentType("正社員");
        engineerMapper.insert(engineer);

        SysUser salesUser = new SysUser();
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
}
