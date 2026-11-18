package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Contract;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EmployeeAttendanceMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.WorkRecordDailyMapper;
import com.ses.service.AttendanceService;
import com.ses.service.WorkRecordService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.service.pwa.PwaClientMutationLedgerService;
import com.ses.service.pwa.PwaMutationTransactionService;
import com.ses.service.pwa.PwaUserContextService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.ses.service.accounting.AccountingTenantContextHolder;

@ExtendWith(MockitoExtension.class)
class PwaMutationApiControllerTest {

    @Mock private PwaClientMutationLedgerService ledger;
    @Spy private PwaMutationTransactionService transactionService = new PwaMutationTransactionService();
    @Mock private PwaUserContextService userContextService;
    @Mock private ObjectMapper objectMapper;
    @Mock private AttendanceService attendanceService;
    @Mock private WorkRecordService workRecordService;
    @Mock private ExpenseRequestService expenseRequestService;
    @Mock private EngineerChangeRequestService changeRequestService;
    @Mock private AttendanceMonthMapper attendanceMonthMapper;
    @Mock private EmployeeAttendanceMapper employeeAttendanceMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private WorkRecordDailyMapper workRecordDailyMapper;
    @Mock private ExpenseRequestMapper expenseRequestMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private HttpServletRequest httpRequest;

    @InjectMocks
    private PwaMutationApiController controller;

    @BeforeEach
    void bindTenant() {
        AccountingTenantContextHolder.setTenantId("tenant-a");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void staleBaseVersionは業務serviceを呼ばずclientとserverの差分を返す() {
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("contractId", 100L).put("workMonth", "2026-08").put("workDate", "2026-08-01")
                .put("startTime", "09:00").put("endTime", "18:00").put("breakMinutes", 60);
        PwaMutationApiController.PwaMutationCommandBody body =
                new PwaMutationApiController.PwaMutationCommandBody("timesheet", "2026-08", payload);
        PwaUserContextService.CurrentContext context =
                new PwaUserContextService.CurrentContext(7L, 9L, "scope-A");
        when(ledger.claim(any(), any(), any(), any(), any()))
                .thenReturn(new PwaClientMutationLedgerService.Claim(1L, context, false, null));
        Contract contract = new Contract();
        contract.setEngineerId(9L);
        when(contractMapper.selectByIdForUpdateForTenant(100L, "tenant-a")).thenReturn(contract);
        WorkRecord record = new WorkRecord();
        record.setId(200L);
        record.setContractId(100L);
        record.setWorkMonth("2026-08");
        record.setVersion(4);
        record.setStatus("入力中");
        when(workRecordMapper.selectByContractIdAndMonthForUpdateForTenant(eq(100L), eq("2026-08"), any())).thenReturn(record);

        assertThatThrownBy(() -> controller.saveTimesheet(body, "req-1", "a".repeat(64), 3,
                System.currentTimeMillis(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .satisfies(error -> {
                    Object data = ((com.ses.common.exception.PwaConflictException) error).getData();
                    assertThat(data.toString()).contains("clientVersion=3", "serverVersion=4");
                });
        verify(ledger).abandon(1L);
    }

    @Test
    void attendanceの初回409は対象日の日次server値を差分へ含める() {
        ObjectMapper realMapper = new ObjectMapper().findAndRegisterModules();
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenAnswer(invocation -> realMapper.convertValue(invocation.getArgument(0), Map.class));
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("workDate", "2026-08-28").put("clockIn", "08:30").put("clockOut", "17:30")
                .put("breakMinutes", 60).put("workType", "通常")
                .put("workplaceType", "客先").put("remarks", "端末値");
        payload.putArray("breaks");
        PwaMutationApiController.PwaMutationCommandBody body =
                new PwaMutationApiController.PwaMutationCommandBody("attendance", "2026-08", payload);
        PwaUserContextService.CurrentContext context =
                new PwaUserContextService.CurrentContext(7L, 9L, "scope-A");
        when(ledger.claim(any(), any(), any(), any(), any()))
                .thenReturn(new PwaClientMutationLedgerService.Claim(2L, context, false, null));
        AttendanceMonth month = new AttendanceMonth();
        month.setId(300L);
        month.setVersion(4);
        month.setStatus("入力中");
        when(attendanceMonthMapper.selectOne(any())).thenReturn(month);
        EmployeeAttendance daily = EmployeeAttendance.builder()
                .engineerId(9L).workDate(java.time.LocalDate.of(2026, 8, 28))
                .clockIn(java.time.LocalTime.of(9, 0)).clockOut(java.time.LocalTime.of(18, 0))
                .breakMinutes(60).workType("通常").workplaceType("客先").remarks("server値").build();
        daily.setId(301L);
        when(employeeAttendanceMapper.selectOne(any())).thenReturn(daily);

        assertThatThrownBy(() -> controller.saveAttendance(body, "req-2", "a".repeat(64), 3,
                System.currentTimeMillis(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .satisfies(error -> {
                    Object data = ((com.ses.common.exception.PwaConflictException) error).getData();
                    assertThat(data.toString()).contains("serverValue=09:00", "serverValue=18:00", "server値");
                });
        verify(ledger).abandon(2L);
    }

    @Test
    void timesheetの初回409は対象日の日次server値を差分へ含める() {
        ObjectMapper realMapper = new ObjectMapper().findAndRegisterModules();
        when(objectMapper.convertValue(any(), eq(Map.class)))
                .thenAnswer(invocation -> realMapper.convertValue(invocation.getArgument(0), Map.class));
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("contractId", 100L).put("workMonth", "2026-08").put("workDate", "2026-08-28")
                .put("startTime", "08:30").put("endTime", "17:30").put("breakMinutes", 60)
                .put("remarks", "端末値");
        PwaMutationApiController.PwaMutationCommandBody body =
                new PwaMutationApiController.PwaMutationCommandBody("timesheet", "2026-08", payload);
        PwaUserContextService.CurrentContext context =
                new PwaUserContextService.CurrentContext(7L, 9L, "scope-A");
        when(ledger.claim(any(), any(), any(), any(), any()))
                .thenReturn(new PwaClientMutationLedgerService.Claim(3L, context, false, null));
        Contract contract = new Contract();
        contract.setEngineerId(9L);
        when(contractMapper.selectByIdForUpdateForTenant(100L, "tenant-a")).thenReturn(contract);
        WorkRecord record = new WorkRecord();
        record.setId(200L);
        record.setContractId(100L);
        record.setWorkMonth("2026-08");
        record.setVersion(4);
        record.setStatus("入力中");
        when(workRecordMapper.selectByContractIdAndMonthForUpdateForTenant(eq(100L), eq("2026-08"), any())).thenReturn(record);
        WorkRecordDaily daily = new WorkRecordDaily();
        daily.setId(201L);
        daily.setWorkRecordId(200L);
        daily.setWorkDate(java.time.LocalDate.of(2026, 8, 28));
        daily.setStartTime(java.time.LocalTime.of(9, 0));
        daily.setEndTime(java.time.LocalTime.of(18, 0));
        daily.setBreakMinutes(60);
        daily.setWorkedHours(new java.math.BigDecimal("8.00"));
        daily.setRemarks("server値");
        when(workRecordDailyMapper.selectOne(any())).thenReturn(daily);

        assertThatThrownBy(() -> controller.saveTimesheet(body, "req-3", "a".repeat(64), 3,
                System.currentTimeMillis(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.PwaConflictException.class)
                .satisfies(error -> {
                    Object data = ((com.ses.common.exception.PwaConflictException) error).getData();
                    assertThat(data.toString()).contains("serverValue=09:00", "serverValue=18:00",
                            "name=remarks, serverValue=server値, clientValue=端末値")
                            .doesNotContain("name=remarks, serverValue=null, clientValue=端末値");
                });
        verify(ledger).abandon(3L);
    }

    @Test
    void 行lockの競合はPWA送信では再送可能な503で返しledgerを放棄してofflineキューの自動再送に乗せる() throws Exception {
        // W3-16b review P2-1: 共通の例外handlerは1213・1205を409にするが、pwa-queue.js は409を人の判断が要る競合
        // （CONFLICT）、5xxを一時的な失敗（queueに残して自動再送）として扱う。PWA送信だけは5xxのまま返す。
        PwaUserContextService.CurrentContext context =
                new PwaUserContextService.CurrentContext(7L, 9L, "scope-A");
        when(ledger.claim(any(), any(), any(), any(), any()))
                .thenReturn(new PwaClientMutationLedgerService.Claim(5L, context, false, null));
        when(contractMapper.selectByIdForUpdateForTenant(100L, "tenant-a"))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock wait timeout",
                        new java.sql.SQLException("lock wait timeout", "HY000", 1205)));
        com.ses.common.exception.GlobalExceptionHandler advice = new com.ses.common.exception.GlobalExceptionHandler();
        org.springframework.context.support.ResourceBundleMessageSource messages =
                new org.springframework.context.support.ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        org.springframework.test.util.ReflectionTestUtils.setField(advice, "messageSource", messages);
        org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).setControllerAdvice(advice).build();
        String body = "{\"screen\":\"timesheet\",\"month\":\"2026-08\",\"payload\":{\"contractId\":100,"
                + "\"workMonth\":\"2026-08\",\"workDate\":\"2026-08-01\",\"startTime\":\"09:00\","
                + "\"endTime\":\"18:00\",\"breakMinutes\":60}}";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/my/pwa/timesheet/daily")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
                        .header("X-Client-Request-Id", "req-lock").header("X-Client-Payload-Hash", "a".repeat(64))
                        .header("X-Client-Base-Version", "4")
                        .header("X-Client-Created-At", String.valueOf(System.currentTimeMillis()))
                        .header("X-User-Scope", "scope-A")
                        .locale(java.util.Locale.JAPAN))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value(503))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("自動で再送")));
        verify(ledger).abandon(5L);
    }

    @Test
    void lockの競合の判定はrollback失敗で覆われた元の例外も見て業務例外は含めない() {
        java.sql.SQLException deadlock = new java.sql.SQLException("deadlock", "40001", 1213);
        org.springframework.transaction.TransactionSystemException wrapped =
                new org.springframework.transaction.TransactionSystemException("rollback failed");
        wrapped.initApplicationException(new org.springframework.dao.DeadlockLoserDataAccessException("deadlock", deadlock));

        assertThat(PwaMutationApiController.isLockConflict(wrapped)).isTrue();
        assertThat(PwaMutationApiController.isLockConflict(new IllegalStateException("outer",
                new org.springframework.dao.CannotAcquireLockException("timeout")))).isTrue();
        assertThat(PwaMutationApiController.isLockConflict(
                com.ses.common.exception.BusinessException.of(409, "error.common.optimisticLock"))).isFalse();
        assertThat(PwaMutationApiController.isLockConflict(new IllegalStateException("other"))).isFalse();
    }

    @Test
    void expense更新はURLとpayloadのID不一致を業務処理前に拒否する() {
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("id", 456L).put("expenseDate", "2026-08-28")
                .put("category", "交通費").put("amount", 1000).put("description", "不一致");
        PwaMutationApiController.PwaMutationCommandBody body =
                new PwaMutationApiController.PwaMutationCommandBody("expense", "2026-08", payload);

        assertThatThrownBy(() -> controller.updateExpenseDraft(123L, body, "req-id", "0".repeat(64), 0,
                System.currentTimeMillis(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasMessage("error.pwa.commandInvalid");
        verifyNoInteractions(ledger, expenseRequestService);
    }

    @Test
    void attendanceはbody月と日付月の不一致を業務処理前に拒否する() {
        ObjectNode payload = new ObjectMapper().createObjectNode()
                .put("workDate", "2026-09-01").putNull("clockIn").putNull("clockOut")
                .putNull("workType").putNull("workplaceType").putNull("remarks");
        payload.putArray("breaks");
        PwaMutationApiController.PwaMutationCommandBody body =
                new PwaMutationApiController.PwaMutationCommandBody("attendance", "2026-08", payload);

        assertThatThrownBy(() -> controller.saveAttendance(body, "req-id", "0".repeat(64), 0,
                System.currentTimeMillis(), "scope-A"))
                .isInstanceOf(com.ses.common.exception.BusinessException.class)
                .hasMessage("error.pwa.commandInvalid");
        verifyNoInteractions(ledger, attendanceService);
    }
}
