package com.ses.service.contract;

import com.ses.dto.contract.RenewalCalendarItemDto;
import com.ses.dto.contract.RenewalCalendarResponseDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.RenewalCalendarService;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RenewalCalendarHealthIntegrationTest {

    @Autowired
    private RenewalCalendarService renewalCalendarService;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private com.ses.mapper.ProjectMapper projectMapper;

    @Autowired
    private ContractMapper contractMapper;

    private Customer testCustomer;
    private Contract testContract;

    @BeforeEach
    void setUp() {
        testCustomer = Customer.builder()
                .companyName("更新カレンダーテスト顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(testCustomer);

        Engineer eng = Engineer.builder()
                .fullName("要員-" + UUID.randomUUID().toString().substring(0, 4))
                .employmentType("正社員")
                .status("稼動中")
                .build();
        engineerMapper.insert(eng);

        Project project = Project.builder()
                .customerId(testCustomer.getId())
                .projectName("検証案件-" + UUID.randomUUID().toString().substring(0, 4))
                .status("募集中")
                .build();
        projectMapper.insert(project);

        testContract = new Contract();
        testContract.setContractNo("CT-RENEW-" + UUID.randomUUID().toString().substring(0, 6));
        testContract.setCustomerId(testCustomer.getId());
        testContract.setEngineerId(eng.getId());
        testContract.setProjectId(project.getId());
        testContract.setStartDate(LocalDate.now().minusMonths(3));
        testContract.setEndDate(LocalDate.now().plusDays(40)); // leadDays=30 の場合、renewalDueDate = +10日後
        testContract.setSellingPrice(new BigDecimal("750000"));
        testContract.setCostPrice(new BigDecimal("550000"));
        testContract.setStatus("稼動中");
        contractMapper.insert(testContract);
    }

    @Test
    @DisplayName("契約更新カレンダーの取得時に顧客のヘルスステータスとスコアが連携されること")
    void testRenewalCalendar_includesCustomerHealth() {
        LocalDate from = LocalDate.now().minusDays(5);
        LocalDate to = LocalDate.now().plusDays(60);

        RenewalCalendarResponseDto response = renewalCalendarService.getCalendar(from, to);

        assertNotNull(response);
        assertNotNull(response.getItems());

        RenewalCalendarItemDto item = response.getItems().stream()
                .filter(i -> testContract.getId().equals(i.getContractId()))
                .findFirst()
                .orElse(null);

        assertNotNull(item, "テスト対象の契約がカレンダーに含まれていること");
        assertNotNull(item.getHealthStatus(), "顧客ヘルスステータスがセットされていること");
        assertNotNull(item.getHealthScore(), "顧客ヘルススコアがセットされていること");
        assertTrue(item.getHealthScore() >= 0 && item.getHealthScore() <= 100);
    }
}
