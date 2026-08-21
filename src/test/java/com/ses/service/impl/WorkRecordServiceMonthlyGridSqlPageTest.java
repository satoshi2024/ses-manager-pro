package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SC-02: 月次勤怠グリッドの SQL ページングと、月次確定が全月対象であることの回帰。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@DisplayName("勤怠月次Grid SQLページング (SC-02)")
class WorkRecordServiceMonthlyGridSqlPageTest {

    @Autowired
    private WorkRecordService workRecordService;

    @SpyBean
    private WorkRecordMapper workRecordMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Test
    @DisplayName("monthlyGridPageはSQL段階でページングし全件selectMonthlyGridを呼ばない")
    void monthlyGridPage_usesSqlPaginationNotSubList() {
        String workMonth = YearMonth.now().toString();
        seedContracts(12, workMonth, "入力中");

        Page<WorkRecordGridDto> page = workRecordService.monthlyGridPage(workMonth, 1L, 5L, null, null);

        assertEquals(12L, page.getTotal());
        assertEquals(5, page.getRecords().size());
        verify(workRecordMapper, never()).selectMonthlyGrid(anyString(), anyString());
        verify(workRecordMapper, atLeastOnce()).selectMonthlyGridPage(
                any(), eq(workMonth), anyString(), isNull(), isNull());
    }

    @Test
    @DisplayName("confirmMonthはページサイズに依存せず対象月の全件を確定する")
    void confirmMonth_coversFullMonthNotCurrentPageOnly() {
        String workMonth = YearMonth.now().toString();
        List<Long> contractIds = seedContracts(6, workMonth, "入力中");

        // 画面は1件だけ見えていても、確定は全月を対象にする。
        Page<WorkRecordGridDto> page = workRecordService.monthlyGridPage(workMonth, 1L, 1L, null, "入力中");
        assertEquals(6L, page.getTotal());
        assertEquals(1, page.getRecords().size());

        workRecordService.confirmMonth(workMonth);

        long confirmed = workRecordMapper.selectCount(new QueryWrapper<WorkRecord>()
                .eq("work_month", workMonth)
                .eq("status", "確定"));
        assertEquals(6L, confirmed, "ページ表示件数ではなく全月の入力中が確定されること");
        assertTrue(contractIds.size() == 6);
    }

    private List<Long> seedContracts(int count, String workMonth, String recordStatus) {
        Customer customer = new Customer();
        customer.setCompanyName("勤怠Grid顧客");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("勤怠Grid案件");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        LocalDate start = YearMonth.parse(workMonth).atDay(1).minusMonths(1);
        for (int i = 1; i <= count; i++) {
            Engineer engineer = new Engineer();
            engineer.setFullName("勤怠要員" + i);
            engineer.setEmploymentType("正社員");
            engineer.setStatus("稼動中");
            engineerMapper.insert(engineer);

            Contract contract = new Contract();
            contract.setContractNo("WR-GRID-" + System.nanoTime() + "-" + i);
            contract.setEngineerId(engineer.getId());
            contract.setProjectId(project.getId());
            contract.setCustomerId(customer.getId());
            contract.setContractType("準委任");
            contract.setStartDate(start);
            contract.setSellingPrice(new BigDecimal("600000"));
            contract.setCostPrice(new BigDecimal("400000"));
            contract.setStatus("稼動中");
            contract.setAcceptanceRequired(Boolean.TRUE);
            contractMapper.insert(contract);
            ids.add(contract.getId());

            WorkRecord record = new WorkRecord();
            record.setContractId(contract.getId());
            record.setWorkMonth(workMonth);
            record.setActualHours(new BigDecimal("160"));
            record.setBillingAmount(new BigDecimal("600000"));
            record.setPaymentAmount(new BigDecimal("400000"));
            record.setStatus(recordStatus);
            workRecordMapper.insert(record);
        }
        return ids;
    }
}
