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
import java.time.LocalDateTime;
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
    private com.ses.mapper.ProjectMapper projectMapper;

    private Customer healthyCustomer;
    private Customer atRiskCustomer;

    @BeforeEach
    void setUp() {
        // 1. 健全な顧客のセットアップ
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

        // QBR 実施履歴
        CustomerQbr qbr = CustomerQbr.builder()
                .customerId(healthyCustomer.getId())
                .meetingDate(LocalDate.now().minusDays(15))
                .title("第1四半期定例会")
                .discussion("要員評価は極めて良好")
                .build();
        qbrMapper.insert(qbr);

        // 2. 危険な顧客のセットアップ（SLA違反あり、契約終了など）
        atRiskCustomer = Customer.builder()
                .companyName("危険顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(atRiskCustomer);

        // 違反リクエストを作成
        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(atRiskCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("システム障害")
                .description("業務停止中")
                .build();
        ServiceRequest sr = serviceRequestService.createRequest(req, 1L, false, null);

        // 解決済みにしてCSAT 1点を回答
        serviceRequestService.changeStatus(sr.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.changeStatus(sr.getId(),
                ServiceRequestStatusChangeRequest.builder().toStatus("RESOLVED").reason("復旧").build(),
                1L, "INTERNAL_USER", "管理者");
        serviceRequestService.submitCsat(sr.getId(),
                PortalCsatCreateRequest.builder().score(1).feedbackComment("復旧まで遅すぎた").build(),
                atRiskCustomer.getId(), 1L);
    }

    @Test
    @DisplayName("健全顧客のヘルススコアが80点以上かつHEALTHYと判定されること")
    void testHealthyCustomer_scoreAndRank() {
        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(healthyCustomer.getId());

        assertNotNull(dto);
        assertEquals(healthyCustomer.getId(), dto.getCustomerId());
        assertTrue(dto.getHealthScore() >= 80, "ヘルススコアは80点以上");
        assertEquals("HEALTHY", dto.getHealthStatus());
        assertEquals(25.0, dto.getEngagementScore(), "有効契約ありで25点");
        assertEquals(20.0, dto.getCommunicationScore(), "QBR実施ありで20点");
    }

    @Test
    @DisplayName("問合せや契約が全くない新規顧客でも減点されず欠損値デフォルトで計算されること")
    void testNewCustomer_missingInputDefaults() {
        Customer newCust = Customer.builder()
                .companyName("新規顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(newCust);

        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(newCust.getId());

        assertNotNull(dto);
        assertEquals(30.0, dto.getSlaComplianceScore(), "問合せ0件時は減点なし30点");
        assertEquals(20.0, dto.getCsatScore(), "CSAT未回答時はデフォルト20点");
        assertEquals(10.0, dto.getEngagementScore(), "有効契約なし時は10点");
        assertEquals(10.0, dto.getCommunicationScore(), "接点なし時は10点");
        assertEquals(70, dto.getHealthScore(), "合計70点 (NEUTRAL)");
        assertEquals("NEUTRAL", dto.getHealthStatus());
    }

    @Test
    @DisplayName("月次スナップショットが正しく生成・永続化されること")
    void testMonthlySnapshot_generation() {
        String currentMonth = "2026-08";
        customerHealthService.generateMonthlySnapshot(currentMonth);

        List<CustomerHealthSnapshot> snapshots = snapshotMapper.selectList(
                new LambdaQueryWrapper<CustomerHealthSnapshot>()
                        .eq(CustomerHealthSnapshot::getSnapshotDate, LocalDate.parse("2026-08-01"))
        );

        assertTrue(snapshots.size() >= 2, "全顧客分のスナップショットが保存されること");

        CustomerHealthSnapshot healthySnapshot = snapshots.stream()
                .filter(s -> s.getCustomerId().equals(healthyCustomer.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(healthySnapshot);
        assertEquals("HEALTHY", healthySnapshot.getHealthStatus());
        assertNotNull(healthySnapshot.getFactorsExplanation());
    }
}
