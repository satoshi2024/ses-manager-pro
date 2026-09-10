package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerHealthServiceTest {

    @Autowired
    private CustomerHealthService customerHealthService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private CustomerHealthSnapshotMapper snapshotMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ServiceSlaClockMapper slaClockMapper;

    private Customer healthyCustomer;
    private Customer atRiskCustomer;

    @BeforeEach
    void setUp() {
        // Service直呼出しでもHTTP tenant filterと同じ前提を明示する。
        AccountingTenantContextHolder.setTenantId("default");
        healthyCustomer = Customer.builder()
                .companyName("健全顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("default")
                .build();
        customerMapper.insert(healthyCustomer);

        Engineer eng1 = Engineer.builder()
                .fullName("稼働エンジニアA")
                .employmentType("正社員")
                .status("稼動中")
                .build();
        engineerMapper.insert(eng1);

        Project p1 = Project.builder()
                .customerId(healthyCustomer.getId())
                .projectName("健全案件")
                .status("募集中")
                .build();
        projectMapper.insert(p1);

        Contract c1 = new Contract();
        c1.setContractNo("CT-HEALTH-" + UUID.randomUUID().toString().substring(0, 6));
        c1.setCustomerId(healthyCustomer.getId());
        c1.setEngineerId(eng1.getId());
        c1.setProjectId(p1.getId());
        c1.setStartDate(LocalDate.now().minusMonths(2));
        c1.setEndDate(LocalDate.now().plusMonths(4));
        c1.setSellingPrice(new BigDecimal("800000"));
        c1.setCostPrice(new BigDecimal("600000"));
        c1.setStatus("稼動中");
        c1.setTenantId("default");
        contractMapper.insert(c1);

        ServiceRequestCreateRequest healthyReq = ServiceRequestCreateRequest.builder()
                .customerId(healthyCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("問い合わせ")
                .description("質問内容")
                .build();
        ServiceRequest srH = serviceRequestService.createRequest(healthyReq, 100L, false, null);
        serviceRequestService.changeStatus(srH.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(srH.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("回答完了").version(1).build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.submitCsat(srH.getId(),
                PortalCsatCreateRequest.builder().score(5).feedbackComment("迅速な対応でした").build(),
                healthyCustomer.getId(), 200L);

        atRiskCustomer = Customer.builder()
                .companyName("危険顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("default")
                .build();
        customerMapper.insert(atRiskCustomer);

        // 未解決 P0 リクエスト (減点: -30点)
        ServiceRequestCreateRequest openReq = ServiceRequestCreateRequest.builder()
                .customerId(atRiskCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("重大障害進行中")
                .description("業務停止中")
                .build();
        serviceRequestService.createRequest(openReq, 100L, false, null);

        // 解決済みだが低CSAT 1点のリクエスト (減点: -15点)
        ServiceRequestCreateRequest req2 = ServiceRequestCreateRequest.builder()
                .customerId(atRiskCustomer.getId())
                .category("QUALITY")
                .priority("P1")
                .subject("過去トラブル")
                .description("障害復旧")
                .build();
        ServiceRequest sr2 = serviceRequestService.createRequest(req2, 100L, false, null);
        serviceRequestService.changeStatus(sr2.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").version(0).build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(sr2.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("復旧").version(1).build(),
                100L, "INTERNAL_USER", "管理者");
        serviceRequestService.submitCsat(sr2.getId(),
                PortalCsatCreateRequest.builder().score(1).feedbackComment("復旧まで遅すぎた").build(),
                atRiskCustomer.getId(), 200L);
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    @DisplayName("健全顧客のヘルススコアが100点減点モデルで80点以上かつHEALTHYと判定されること")
    void testHealthyCustomer_scoreAndRank() {
        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(healthyCustomer.getId());

        assertNotNull(dto);
        assertEquals(healthyCustomer.getId(), dto.getCustomerId());
        assertTrue(dto.getHealthScore() >= 80, "減点なし/軽微で80点以上");
        assertEquals("HEALTHY", dto.getHealthStatus());
        assertEquals(0, dto.getOpenCriticalIssuesCount(), "未解決P0/P1は0件");
        assertEquals(BigDecimal.valueOf(5.0).setScale(2), dto.getAvgCsatScore(), "CSATは5.0");
    }

    @Test
    @DisplayName("未解決重大障害や低CSATがある危険顧客が減点されること (100 - 30 - 15 = 55点 WARNING)")
    void testAtRiskCustomer_scoreDeduction() {
        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(atRiskCustomer.getId());

        assertNotNull(dto);
        assertEquals(atRiskCustomer.getId(), dto.getCustomerId());
        assertEquals(55, dto.getHealthScore(), "減点により55点");
        assertEquals("WARNING", dto.getHealthStatus());
        assertEquals(1, dto.getOpenCriticalIssuesCount(), "未解決P0が1件");
    }

    @Test
    @DisplayName("問合せやCSATが全くない新規顧客でも減点されず欠損値リストに記録されること")
    void testNewCustomer_missingInputTracking() {
        Customer newCust = Customer.builder()
                .companyName("新規顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("default")
                .build();
        customerMapper.insert(newCust);
        serviceRequestService.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(newCust.getId()).category("OTHER").priority("P3")
                .subject("新規顧客初回問い合わせ").description("tenant所有権fixture").build(),
                100L, false, null);

        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(newCust.getId());

        assertNotNull(dto);
        assertEquals(100, dto.getHealthScore(), "減点要素なしで100点 (HEALTHY)");
        assertEquals("HEALTHY", dto.getHealthStatus());
        assertTrue(dto.getMissingInputs().contains("CSAT"), "CSATが欠損値として記録されること");
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("Service Requestが無いtenant所有顧客も一覧・詳細・月次snapshotの母集団に残ること")
    void noRequestCustomer_remainsInHealthPopulation() {
        Customer customer = Customer.builder()
                .companyName("問い合わせ無し顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("default")
                .build();
        customerMapper.insert(customer);

        List<CustomerHealthScoreDto> list = customerHealthService.listCustomerHealthSummaries(null, customer.getCompanyName());
        assertEquals(1, list.size());
        assertEquals(customer.getId(), list.get(0).getCustomerId());
        assertEquals(customer.getId(), customerHealthService.calculateCustomerHealth(customer.getId()).getCustomerId());

        String targetMonth = YearMonth.now().toString();
        customerHealthService.generateMonthlySnapshot(targetMonth, "no-request検証");
        assertTrue(snapshotMapper.selectList(new LambdaQueryWrapper<CustomerHealthSnapshot>()
                .eq(CustomerHealthSnapshot::getCustomerId, customer.getId())
                .eq(CustomerHealthSnapshot::getSnapshotDate, LocalDate.parse(targetMonth + "-01"))).size() >= 1);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("Customer Healthの顧客母集団はtenant単位で一覧・詳細・snapshotを分離すること")
    void tenantOwnership_isSharedByListDetailAndSnapshot() {
        Customer tenantACustomer = Customer.builder()
                .companyName("tenant-a 顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("tenant-a")
                .build();
        Customer tenantBCustomer = Customer.builder()
                .companyName("tenant-b 顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("tenant-b")
                .build();
        customerMapper.insert(tenantACustomer);
        customerMapper.insert(tenantBCustomer);

        AccountingTenantContextHolder.setTenantId("tenant-a");
        List<CustomerHealthScoreDto> tenantAList = customerHealthService
                .listCustomerHealthSummaries(null, tenantACustomer.getCompanyName());
        assertEquals(List.of(tenantACustomer.getId()), tenantAList.stream()
                .map(CustomerHealthScoreDto::getCustomerId).toList());
        assertThrows(BusinessException.class,
                () -> customerHealthService.calculateCustomerHealth(tenantBCustomer.getId()));
        customerHealthService.generateMonthlySnapshot(YearMonth.now().toString(), "tenant-a snapshot");
        assertTrue(snapshotMapper.selectList(new LambdaQueryWrapper<CustomerHealthSnapshot>()
                .eq(CustomerHealthSnapshot::getCustomerId, tenantACustomer.getId())).size() >= 1);
        assertTrue(snapshotMapper.selectList(new LambdaQueryWrapper<CustomerHealthSnapshot>()
                .eq(CustomerHealthSnapshot::getCustomerId, tenantBCustomer.getId())).isEmpty());

        AccountingTenantContextHolder.setTenantId("tenant-b");
        List<CustomerHealthScoreDto> tenantBList = customerHealthService
                .listCustomerHealthSummaries(null, tenantBCustomer.getCompanyName());
        assertEquals(List.of(tenantBCustomer.getId()), tenantBList.stream()
                .map(CustomerHealthScoreDto::getCustomerId).toList());
        assertEquals(tenantBCustomer.getId(), customerHealthService
                .calculateCustomerHealth(tenantBCustomer.getId()).getCustomerId());
    }

    @Test
    @DisplayName("SLA30日集計はround作成時刻ではなく実際のresponse/resolve breach時刻を使うこと")
    void testSlaBreachCount_usesActualBreachTimestamps() {
        Customer customer = Customer.builder()
                .companyName("SLA時刻顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .tenantId("default")
                .build();
        customerMapper.insert(customer);
        ServiceRequest request = serviceRequestService.createRequest(ServiceRequestCreateRequest.builder()
                .customerId(customer.getId()).category("SYSTEM").priority("P2")
                .subject("SLA時刻検証").description("breach時刻検証").build(), 100L, false, null);
        ServiceSlaClock clock = slaClockMapper.selectOne(new LambdaQueryWrapper<ServiceSlaClock>()
                .eq(ServiceSlaClock::getServiceRequestId, request.getId()));
        LocalDateTime now = LocalDateTime.now();
        clock.setCreatedAt(now.minusDays(60));
        clock.setResponseBreached(true);
        clock.setResponseBreachedAt(now.minusDays(5));
        clock.setResolveBreached(false);
        clock.setResolveBreachedAt(null);
        slaClockMapper.updateById(clock);

        CustomerHealthScoreDto recent = customerHealthService.calculateCustomerHealth(customer.getId());
        assertEquals(1, recent.getSlaBreachCount30d());

        clock.setResponseBreachedAt(now.minusDays(31));
        slaClockMapper.updateById(clock);
        CustomerHealthScoreDto old = customerHealthService.calculateCustomerHealth(customer.getId());
        assertEquals(0, old.getSlaBreachCount30d());
    }

    @Test
    @DisplayName("非管理者（営業ロール）によるService層直接呼び出しは403例外")
    @WithMockUser(username = "salesuser", roles = {"営業"})
    void testGenerateMonthlySnapshot_salesRole_throws403() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                customerHealthService.generateMonthlySnapshot("2026-08", "手動実行")
        );
        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("SecurityContextなしのスナップショット直接呼出しはデフォルト拒否されること")
    void testGenerateMonthlySnapshot_withoutSecurityContext_throws403() {
        SecurityContextHolder.clearContext();

        BusinessException ex = assertThrows(BusinessException.class, () ->
                customerHealthService.generateMonthlySnapshot(YearMonth.now().toString(), "未認証実行")
        );

        assertEquals(403, ex.getCode());
    }

    @Test
    @DisplayName("明示されたscheduler実行主体だけがSecurityContextなしでスナップショットを実行できること")
    void testGenerateMonthlySnapshot_controlledScheduler_isAllowed() {
        SecurityContextHolder.clearContext();

        customerHealthService.generateMonthlySnapshot(YearMonth.now().toString(), "定期実行",
                SnapshotExecutionContext.systemScheduler());
    }

    @Test
    @DisplayName("不正なtargetMonth形式は400例外")
    @WithMockUser(username = "admin", roles = {"管理者"})
    void testGenerateMonthlySnapshot_invalidMonth_throws400() {
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                customerHealthService.generateMonthlySnapshot("2026-13", "不正月")
        );
        assertEquals(400, ex1.getCode());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                customerHealthService.generateMonthlySnapshot("invalid-month", "不正月")
        );
        assertEquals(400, ex2.getCode());
    }

    @Test
    @DisplayName("同一月スナップショットの冪等性とデータ変更時の版数インクリメント(非破壊リビジョン)")
    @WithMockUser(username = "admin", roles = {"管理者"})
    void testMonthlySnapshot_idempotencyAndVersionIncrement() {
        String targetMonth = YearMonth.now().toString();
        LocalDate snapshotDate = LocalDate.parse(targetMonth + "-01");

        // 1回目実行
        customerHealthService.generateMonthlySnapshot(targetMonth, "初回作成");
        List<CustomerHealthSnapshot> list1 = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
        );
        assertTrue(list1.size() >= 2);
        for (CustomerHealthSnapshot s : list1) {
            assertEquals(1, s.getVersionNo());
            assertEquals(Boolean.TRUE, s.getIsCurrent());
        }

        // 2回目実行（データ変更なし） -> 冪等にスキップされ、行数・版数は変わらない
        customerHealthService.generateMonthlySnapshot(targetMonth, "2回目実行（データ変化なし）");
        List<CustomerHealthSnapshot> list2 = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
        );
        assertEquals(list1.size(), list2.size());

        // 顧客データを更新（健全顧客にP0障害を追加してスコア低下）
        ServiceRequestCreateRequest criticalReq = ServiceRequestCreateRequest.builder()
                .customerId(healthyCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("緊急障害発生")
                .description("業務停止")
                .build();
        serviceRequestService.createRequest(criticalReq, 100L, false, null);

        // 3回目実行（データ変化あり） -> 非破壊で version 2 が追記され、旧版は更新されない
        customerHealthService.generateMonthlySnapshot(targetMonth, "障害発生に伴う修正スナップショット");
        List<CustomerHealthSnapshot> healthySnapshots = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getCustomerId, healthyCustomer.getId())
                        .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
                        .orderByAsc(CustomerHealthSnapshot::getVersionNo)
        );
        assertEquals(2, healthySnapshots.size(), "旧版と新版の両方が保持されること");
        CustomerHealthSnapshot v1 = healthySnapshots.get(0);
        CustomerHealthSnapshot v2 = healthySnapshots.get(1);

        assertEquals(1, v1.getVersionNo());
        assertEquals(Boolean.TRUE, v1.getIsCurrent(), "旧版が追記専用で保持されること");

        assertEquals(2, v2.getVersionNo());
        assertEquals(Boolean.TRUE, v2.getIsCurrent(), "新版は current=true であること");
        assertEquals("障害発生に伴う修正スナップショット", v2.getRevisionReason());
        assertTrue(v2.getTotalScore() < v1.getTotalScore(), "P0追加により新版スコアが低下していること");
    }
}
