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
import com.ses.service.pwa.PwaUserContextService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PwaMutationApiControllerTest {

    @Mock private PwaClientMutationLedgerService ledger;
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
        when(contractMapper.selectByIdForUpdate(100L)).thenReturn(contract);
        WorkRecord record = new WorkRecord();
        record.setId(200L);
        record.setContractId(100L);
        record.setWorkMonth("2026-08");
        record.setVersion(4);
        record.setStatus("入力中");
        when(workRecordMapper.selectOne(any())).thenReturn(record);

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
        when(contractMapper.selectByIdForUpdate(100L)).thenReturn(contract);
        WorkRecord record = new WorkRecord();
        record.setId(200L);
        record.setContractId(100L);
        record.setWorkMonth("2026-08");
        record.setVersion(4);
        record.setStatus("入力中");
        when(workRecordMapper.selectOne(any())).thenReturn(record);
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
