package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.util.PageUtils;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.workrecord.PendingApprovalSummaryDto;
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
import java.time.LocalDateTime;
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
 * P2-2: 承認滞留サマリも提出済のみを SQL ページングし、全件 monthlyGrid に依存しない。
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

    @Test
    @DisplayName("pendingApprovalSummaryは提出済のみSQLページングし全件monthlyGridを呼ばない")
    void pendingApprovalSummary_usesSqlPendingPageNotFullMonthlyGrid() {
        String workMonth = YearMonth.now().toString();
        // 非提出済を大量に置き、提出済は少数だけ。全件グリッド経由だと非提出済も読むことになる。
        seedContracts(20, workMonth, "入力中");
        seedContractsWithUpdatedAt(3, workMonth, "提出済",
                LocalDateTime.now().minusDays(7),
                LocalDateTime.now().minusDays(3),
                LocalDateTime.now().minusDays(1));

        PendingApprovalSummaryDto summary = workRecordService.pendingApprovalSummary(workMonth, 1L, 2L);

        assertEquals(3, summary.getSubmittedCount());
        assertEquals(2, summary.getItems().size());
        assertEquals(7, summary.getMaxPendingDays());
        assertEquals(7, summary.getItems().get(0).getDaysPending());
        verify(workRecordMapper, never()).selectMonthlyGrid(anyString(), anyString());
        verify(workRecordMapper, never()).selectMonthlyGridScoped(anyString(), anyString(), any(), any(Boolean.class), any(), any(), any());
        verify(workRecordMapper, atLeastOnce()).selectPendingApprovalPage(any(), eq(workMonth), anyString());
    }

    @Test
    @DisplayName("pendingApprovalSummaryのpage sizeはPageUtilsで上限に丸められる")
    void pendingApprovalSummary_clampsPageSizeViaPageUtils() {
        String workMonth = YearMonth.now().toString();
        seedContractsWithUpdatedAt(2, workMonth, "提出済",
                LocalDateTime.now().minusDays(2),
                LocalDateTime.now().minusDays(1));

        // MAX_PAGE_SIZE(1000) を超える size を渡しても、SQL へ渡る Page は丸め後の値になる。
        workRecordService.pendingApprovalSummary(workMonth, 1L, PageUtils.MAX_PAGE_SIZE + 500);

        verify(workRecordMapper).selectPendingApprovalPage(
                org.mockito.ArgumentMatchers.argThat(page ->
                        page != null && page.getSize() == PageUtils.MAX_PAGE_SIZE && page.getCurrent() == 1L),
                eq(workMonth),
                anyString());
        verify(workRecordMapper, never()).selectMonthlyGrid(anyString(), anyString());
    }

    private List<Long> seedContracts(int count, String workMonth, String recordStatus) {
        return seedContractsWithUpdatedAt(count, workMonth, recordStatus);
    }

    private List<Long> seedContractsWithUpdatedAt(int count, String workMonth, String recordStatus,
                                                   LocalDateTime... updatedAts) {
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
            if (updatedAts != null && updatedAts.length >= i) {
                record.setUpdatedAt(updatedAts[i - 1]);
            }
            workRecordMapper.insert(record);
            if (updatedAts != null && updatedAts.length >= i) {
                // insert 時の DEFAULT/ON UPDATE を上書きして滞留日数を安定させる。
                workRecordMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<WorkRecord>()
                        .eq("id", record.getId())
                        .set("updated_at", updatedAts[i - 1]));
            }
        }
        return ids;
    }
}
