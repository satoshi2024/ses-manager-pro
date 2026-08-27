package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.CustomerHealthSnapshot;
import com.ses.entity.CustomerQbr;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerHealthSnapshotMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerQbrMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
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
    private CustomerQbrMapper qbrMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private CustomerHealthSnapshotMapper snapshotMapper;

    @Autowired
    private ProjectMapper projectMapper;

    private Customer healthyCustomer;
    private Customer atRiskCustomer;

    @BeforeEach
    void setUp() {
        // 1. 健全な顧客のセットアップ（未解決障害なし、良好CSAT）
        healthyCustomer = Customer.builder()
                .companyName("健全顧客-" + UUID.randomUUID().toString().substring(0, 6))
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
        contractMapper.insert(c1);

        // 解決済みリクエストと高CSAT (5点)
        ServiceRequestCreateRequest healthyReq = ServiceRequestCreateRequest.builder()
                .customerId(healthyCustomer.getId())
                .category("SYSTEM")
                .priority("P2")
                .subject("問い合わせ")
                .description("質問内容")
                .build();
        ServiceRequest srH = serviceRequestService.createRequest(healthyReq, 1L, false, null);
        serviceRequestService.changeStatus(srH.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(srH.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("回答完了").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.submitCsat(srH.getId(),
                PortalCsatCreateRequest.builder().score(5).feedbackComment("迅速な対応でした").build(),
                healthyCustomer.getId(), 1L);

        // 2. 危険な顧客のセットアップ（未解決P0障害、低CSAT）
        atRiskCustomer = Customer.builder()
                .companyName("危険顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(atRiskCustomer);

        // 未解決 P0 リクエスト (減点: -20点)
        ServiceRequestCreateRequest openReq = ServiceRequestCreateRequest.builder()
                .customerId(atRiskCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("重大障害進行中")
                .description("業務停止中")
                .build();
        serviceRequestService.createRequest(openReq, 1L, false, null);

        // 解決済みだが低CSAT 1点のリクエスト (減点: -30点)
        ServiceRequestCreateRequest req2 = ServiceRequestCreateRequest.builder()
                .customerId(atRiskCustomer.getId())
                .category("QUALITY")
                .priority("P1")
                .subject("過去トラブル")
                .description("障害復旧")
                .build();
        ServiceRequest sr2 = serviceRequestService.createRequest(req2, 1L, false, null);
        serviceRequestService.changeStatus(sr2.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(sr2.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("復旧").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.submitCsat(sr2.getId(),
                PortalCsatCreateRequest.builder().score(1).feedbackComment("復旧まで遅すぎた").build(),
                atRiskCustomer.getId(), 1L);
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
    @DisplayName("未解決重大障害や低CSATがある危険顧客が減点されCRITICALと判定されること")
    void testAtRiskCustomer_scoreDeduction() {
        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(atRiskCustomer.getId());

        assertNotNull(dto);
        assertEquals(atRiskCustomer.getId(), dto.getCustomerId());
        // 未解決P0 (-30点) + CSAT 1.0 (-30点) = 40点 (CRITICAL)
        assertEquals(40, dto.getHealthScore(), "減点により40点");
        assertEquals("CRITICAL", dto.getHealthStatus());
        assertEquals(1, dto.getOpenCriticalIssuesCount(), "未解決P0が1件");
    }

    @Test
    @DisplayName("問合せやCSATが全くない新規顧客でも減点されず欠損値リストに記録されること")
    void testNewCustomer_missingInputTracking() {
        Customer newCust = Customer.builder()
                .companyName("新規顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(newCust);

        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(newCust.getId());

        assertNotNull(dto);
        assertEquals(100, dto.getHealthScore(), "減点要素なしで100点 (HEALTHY)");
        assertEquals("HEALTHY", dto.getHealthStatus());
        assertTrue(dto.getMissingInputs().contains("CSAT"), "CSATが欠損値として記録されること");
    }

    @Test
    @DisplayName("月次スナップショットが正しく生成・非破壊更新されること")
    void testMonthlySnapshot_generationAndNonDestructiveUpdate() {
        String currentMonth = "2026-08";
        customerHealthService.generateMonthlySnapshot(currentMonth);

        LocalDate snapshotDate = LocalDate.parse(currentMonth + "-01");
        List<CustomerHealthSnapshot> list1 = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
        );
        assertTrue(list1.size() >= 2, "全顧客分のスナップショットが生成されること");

        // 2回目の実行でも重複エラーにならず更新されること
        customerHealthService.generateMonthlySnapshot(currentMonth);
        List<CustomerHealthSnapshot> list2 = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getSnapshotDate, snapshotDate)
        );
        assertEquals(list1.size(), list2.size(), "重複行が作成されず件数が維持されること");
    }
}
