package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.exception.PwaConflictException;
import com.ses.common.result.ApiResult;
import com.ses.dto.attendance.AttendanceDayRequest;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Contract;
import com.ses.entity.EmployeeAttendance;
import com.ses.entity.Engineer;
import com.ses.entity.ExpenseRequest;
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
import com.ses.service.pwa.PwaMutationCommand;
import com.ses.service.pwa.PwaMutationTransactionService;
import com.ses.service.pwa.PwaUserContextService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 要員PWAでoffline許可されたdraft/daily mutationだけの入口。
 * submit/approve/reject/close/cancel/withdrawや添付はこのcontrollerへ追加しない。
 */
@RestController
@RequestMapping("/api/my/pwa")
@RequiredArgsConstructor
public class PwaMutationApiController {
    private final PwaClientMutationLedgerService ledger;
    private final PwaMutationTransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final AttendanceService attendanceService;
    private final WorkRecordService workRecordService;
    private final ExpenseRequestService expenseRequestService;
    private final EngineerChangeRequestService changeRequestService;
    private final AttendanceMonthMapper attendanceMonthMapper;
    private final EmployeeAttendanceMapper employeeAttendanceMapper;
    private final ContractMapper contractMapper;
    private final WorkRecordMapper workRecordMapper;
    private final WorkRecordDailyMapper workRecordDailyMapper;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final EngineerMapper engineerMapper;
    private final HttpServletRequest httpRequest;

    @PostMapping("/attendance/daily")
    public ApiResult<Object> saveAttendance(@RequestBody PwaMutationCommandBody body,
                                            @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                            @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                            @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                            @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                            @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "attendance", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            AttendanceDayRequest request = read(body.payload(), AttendanceDayRequest.class);
            attendanceService.saveMyDay(request);
            return null;
        });
    }

    @DeleteMapping("/attendance/daily")
    public ApiResult<Object> deleteAttendance(@RequestBody PwaMutationCommandBody body,
                                              @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                              @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                              @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                              @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                              @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "attendance", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            String month = text(body.payload(), "month", body.month());
            attendanceService.deleteMyDay(month, requiredText(body.payload(), "workDate"));
            return null;
        });
    }

    @PostMapping("/timesheet/daily")
    public ApiResult<Object> saveTimesheet(@RequestBody PwaMutationCommandBody body,
                                           @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                           @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                           @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                           @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                           @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "timesheet", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            JsonNode payload = body.payload();
            Long contractId = requiredLong(payload, "contractId");
            assertOwnedContract(context.engineerId(), contractId);
            WorkRecordDaily daily = read(payload, WorkRecordDaily.class);
            daily.setWorkDate(LocalDate.parse(requiredText(payload, "workDate")));
            WorkRecord record = workRecordService.saveDaily(contractId,
                    text(payload, "workMonth", body.month()), daily);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", record.getId());
            result.put("actualHours", record.getActualHours());
            result.put("status", record.getStatus());
            return result;
        });
    }

    @DeleteMapping("/timesheet/daily")
    public ApiResult<Object> deleteTimesheet(@RequestBody PwaMutationCommandBody body,
                                             @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                             @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                             @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                             @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                             @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "timesheet", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            JsonNode payload = body.payload();
            Long contractId = requiredLong(payload, "contractId");
            assertOwnedContract(context.engineerId(), contractId);
            workRecordService.deleteDaily(contractId, text(payload, "workMonth", body.month()),
                    LocalDate.parse(requiredText(payload, "workDate")));
            return null;
        });
    }

    @PostMapping("/expenses/drafts")
    public ApiResult<Object> createExpenseDraft(@RequestBody PwaMutationCommandBody body,
                                                @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                                @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                                @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                                @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                                @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "expense", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            return expenseAck(expenseRequestService.createDraft(context.engineerId(), expenseCommand(body.payload())));
        });
    }

    @PutMapping("/expenses/drafts/{id}")
    public ApiResult<Object> updateExpenseDraft(@PathVariable Long id, @RequestBody PwaMutationCommandBody body,
                                                @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                                @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                                @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                                @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                                @RequestHeader("X-User-Scope") String userScope) {
        assertExpensePathId(id, body);
        return run(body, "expense", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context ->
                expenseAck(expenseRequestService.updateDraft(context.engineerId(), id, expenseCommand(body.payload()))));
    }

    @PostMapping("/change-requests/drafts")
    public ApiResult<Object> createChangeRequestDraft(@RequestBody PwaMutationCommandBody body,
                                                       @RequestHeader("X-Client-Request-Id") String clientRequestId,
                                               @RequestHeader("X-Client-Payload-Hash") String payloadHash,
                                               @RequestHeader("X-Client-Base-Version") Integer baseVersion,
                                               @RequestHeader("X-Client-Created-At") Long clientCreatedAt,
                                               @RequestHeader("X-User-Scope") String userScope) {
        return run(body, "change-request", clientRequestId, payloadHash, baseVersion, clientCreatedAt, userScope, context -> {
            JsonNode payload = body.payload();
            if (!payload.path("attachmentDocumentId").isMissingNode()
                    && !payload.path("attachmentDocumentId").isNull()) {
                throw BusinessException.of(400, "error.pwa.onlineOnly");
            }
            Map<String, Object> changePayload = objectMapper.convertValue(
                    payload.path("payload"), Map.class);
            EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(
                    context.engineerId(), requiredText(payload, "requestType"), changePayload,
                    text(payload, "reason", null), null);
            return changeRequestAck(draft);
        });
    }

    private ApiResult<Object> run(PwaMutationCommandBody body, String expectedScreen,
                                  String clientRequestId, String payloadHash, Integer baseVersion,
                                  Long clientCreatedAt, String userScope,
                                  Function<PwaUserContextService.CurrentContext, Object> action) {
        return transactionService.execute(() -> runInTransaction(body, expectedScreen, clientRequestId,
                payloadHash, baseVersion, clientCreatedAt, userScope, action));
    }

    private ApiResult<Object> runInTransaction(PwaMutationCommandBody body, String expectedScreen,
                                               String clientRequestId, String payloadHash, Integer baseVersion,
                                               Long clientCreatedAt, String userScope,
                                               Function<PwaUserContextService.CurrentContext, Object> action) {
        if (body == null || body.payload() == null || !expectedScreen.equals(body.screen())) {
            throw BusinessException.of(400, "error.pwa.commandInvalid");
        }
        if (baseVersion == null || baseVersion < 0) {
            throw BusinessException.of(400, "error.pwa.baseVersionRequired");
        }
        PwaMutationCommand command = new PwaMutationCommand(
                operation(expectedScreen, httpRequest.getMethod(), httpRequest.getRequestURI()),
                body.screen(), body.month(), baseVersion, body.payload());
        validateDateMonthBoundary(command);
        PwaClientMutationLedgerService.Claim claim = ledger.claim(command, clientRequestId, payloadHash,
                clientCreatedAt, userScope);
        httpRequest.setAttribute(PwaClientMutationLedgerService.REPLAY_REQUEST_ATTRIBUTE, claim.replay());
        if (claim.replay()) return ApiResult.success(claim.responseData());
        try {
            assertBaseVersion(command, claim.context());
            Object result = action.apply(claim.context());
            // 同一aggregateへ複数のoffline commandを順番に適用できるよう、適用後versionだけを返す。
            VersionSnapshot after = snapshot(command, claim.context());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result);
            response.put("version", after.version());
            ledger.complete(claim.mutationId(), response);
            return ApiResult.success(response);
        } catch (PwaConflictException | BusinessException e) {
            ledger.abandon(claim.mutationId());
            if (e instanceof BusinessException business && business.getCode() == 409) {
                throw new PwaConflictException("pwa.staleBaseVersion", conflictData(command, claim.context()));
            }
            throw e;
        } catch (RuntimeException e) {
            ledger.abandon(claim.mutationId());
            throw e;
        }
    }

    private void assertExpensePathId(Long pathId, PwaMutationCommandBody body) {
        if (pathId == null || body == null || body.payload() == null
                || !pathId.equals(requiredLong(body.payload(), "id"))) {
            throw BusinessException.of(400, "error.pwa.commandInvalid");
        }
    }

    /** versionを比較する月と、既存domain serviceが実際に更新する日付の月を一致させる。 */
    private void validateDateMonthBoundary(PwaMutationCommand command) {
        if (!"attendance".equals(command.screen()) && !"timesheet".equals(command.screen())) return;
        JsonNode payload = command.payload();
        String month = "attendance".equals(command.screen())
                ? text(payload, "month", command.month())
                : text(payload, "workMonth", command.month());
        String workDate = requiredText(payload, "workDate");
        try {
            if (month == null || !YearMonth.parse(month).equals(YearMonth.from(LocalDate.parse(workDate)))
                    || !month.equals(command.month())) {
                throw BusinessException.of(400, "error.pwa.commandInvalid");
            }
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(400, "error.pwa.commandInvalid");
        }
    }

    private void assertBaseVersion(PwaMutationCommand command,
                                   PwaUserContextService.CurrentContext context) {
        VersionSnapshot snapshot = snapshot(command, context);
        if (!Objects.equals(command.baseVersion(), snapshot.version())) {
            throw new PwaConflictException("pwa.staleBaseVersion", conflictData(command, context, snapshot));
        }
    }

    private Map<String, Object> conflictData(PwaMutationCommand command,
                                              PwaUserContextService.CurrentContext context) {
        return conflictData(command, context, snapshot(command, context));
    }

    private Map<String, Object> conflictData(PwaMutationCommand command,
                                              PwaUserContextService.CurrentContext context,
                                              VersionSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "STALE_BASE_VERSION");
        data.put("screen", command.screen());
        data.put("resource", command.screen());
        data.put("resourceId", resourceId(command, context, snapshot));
        data.put("clientVersion", command.baseVersion());
        data.put("serverVersion", snapshot.version());
        data.put("server", snapshot.data());
        data.put("client", command.payload());
        data.put("fields", conflictFields(command, snapshot));
        return data;
    }

    /** 競合を別aggregateへ誤適用しないため、画面名だけでなく対象識別子も返す。 */
    private String resourceId(PwaMutationCommand command,
                              PwaUserContextService.CurrentContext context,
                              VersionSnapshot snapshot) {
        Object id = snapshot.data().get("id");
        if (id != null) return String.valueOf(id);
        JsonNode payload = command.payload();
        if ("attendance".equals(command.screen())) {
            return context.engineerId() + ":" + text(payload, "month", command.month());
        }
        if ("timesheet".equals(command.screen())) {
            return requiredLong(payload, "contractId") + ":" + text(payload, "workMonth", command.month());
        }
        if ("expense".equals(command.screen())) {
            return "new:" + context.engineerId() + ":" + command.month();
        }
        return String.valueOf(context.engineerId());
    }

    /** server/client双方の同名項目とbase versionを、JSON全体とは別に画面表示用へ整形する。 */
    private List<Map<String, Object>> conflictFields(PwaMutationCommand command,
                                                      VersionSnapshot snapshot) {
        Map<String, Object> convertedServer = objectMapper.convertValue(snapshot.data(), Map.class);
        Map<String, Object> convertedClient = objectMapper.convertValue(command.payload(), Map.class);
        final Map<String, Object> server = convertedServer == null ? new LinkedHashMap<>() : convertedServer;
        final Map<String, Object> client = convertedClient == null ? new LinkedHashMap<>() : convertedClient;
        client.put("version", command.baseVersion());
        Set<String> names = new LinkedHashSet<>();
        names.add("version");
        names.addAll(server.keySet());
        names.addAll(client.keySet());
        return names.stream()
                .filter(name -> !"exists".equals(name))
                .map(name -> {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("name", name);
                    field.put("serverValue", server.get(name));
                    field.put("clientValue", client.get(name));
                    return field;
                })
                .toList();
    }

    private VersionSnapshot snapshot(PwaMutationCommand command,
                                     PwaUserContextService.CurrentContext context) {
        JsonNode payload = command.payload();
        if ("attendance".equals(command.screen())) {
            String month = text(payload, "month", command.month());
            AttendanceMonth row = attendanceMonthMapper.selectOne(new LambdaQueryWrapper<AttendanceMonth>()
                    .eq(AttendanceMonth::getEngineerId, context.engineerId())
                    .eq(AttendanceMonth::getWorkMonth, YearMonth.parse(month).atDay(1))
                    .last("LIMIT 1 FOR UPDATE"));
            if (row == null) return new VersionSnapshot(0, Map.of("exists", false));
            EmployeeAttendance daily = employeeAttendanceMapper.selectOne(new LambdaQueryWrapper<EmployeeAttendance>()
                    .eq(EmployeeAttendance::getEngineerId, context.engineerId())
                    .eq(EmployeeAttendance::getWorkDate, LocalDate.parse(requiredText(payload, "workDate")))
                    .eq(EmployeeAttendance::getSource, "manual")
                    .orderByDesc(EmployeeAttendance::getId)
                    .last("LIMIT 1 FOR UPDATE"));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exists", daily != null);
            // 月次versionを競合対象として保持し、対象日の保存値を同じsnapshotへ正規化する。
            data.put("id", row.getId());
            data.put("version", value(row.getVersion()));
            data.put("status", row.getStatus());
            data.put("workDate", requiredText(payload, "workDate"));
            if (daily != null) {
                data.put("dailyId", daily.getId());
                data.put("clockIn", daily.getClockIn() == null ? null : daily.getClockIn().toString());
                data.put("clockOut", daily.getClockOut() == null ? null : daily.getClockOut().toString());
                data.put("breakMinutes", daily.getBreakMinutes());
                data.put("workType", daily.getWorkType());
                data.put("workplaceType", daily.getWorkplaceType());
                data.put("remarks", daily.getRemarks());
            }
            return new VersionSnapshot(value(row.getVersion()), data);
        }
        if ("timesheet".equals(command.screen())) {
            Long contractId = requiredLong(payload, "contractId");
            // 既存の勤怠更新・月次確定と同じ Contract -> WorkRecord 順でロックする。
            // 先に WorkRecord をロックすると、月次確定（Contract -> WorkRecord）との相互待機になる。
            Contract contract = contractMapper.selectByIdForUpdate(contractId);
            if (contract == null) throw BusinessException.of(404, "error.workRecord.noContract2");
            if (!Objects.equals(context.engineerId(), contract.getEngineerId())) {
                throw BusinessException.of(403, "error.my.notOwner");
            }
            String month = text(payload, "workMonth", command.month());
            WorkRecord row = workRecordMapper.selectOne(new LambdaQueryWrapper<WorkRecord>()
                    .eq(WorkRecord::getContractId, contractId)
                    .eq(WorkRecord::getWorkMonth, month)
                    .last("LIMIT 1 FOR UPDATE"));
            if (row == null) return new VersionSnapshot(0, Map.of("exists", false));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exists", true); data.put("id", row.getId()); data.put("version", value(row.getVersion()));
            data.put("status", row.getStatus()); data.put("actualHours", row.getActualHours());
            data.put("monthlyRemarks", row.getRemarks());
            WorkRecordDaily daily = workRecordDailyMapper.selectOne(new LambdaQueryWrapper<WorkRecordDaily>()
                    .eq(WorkRecordDaily::getWorkRecordId, row.getId())
                    .eq(WorkRecordDaily::getWorkDate, LocalDate.parse(requiredText(payload, "workDate")))
                    .last("LIMIT 1 FOR UPDATE"));
            data.put("workDate", requiredText(payload, "workDate"));
            if (daily != null) {
                data.put("dailyId", daily.getId());
                data.put("startTime", daily.getStartTime() == null ? null : daily.getStartTime().toString());
                data.put("endTime", daily.getEndTime() == null ? null : daily.getEndTime().toString());
                data.put("breakMinutes", daily.getBreakMinutes());
                data.put("workedHours", daily.getWorkedHours());
                // クライアントの日次payloadと既存GETレスポンスのキーを揃え、
                // 月次remarksを日次remarksとして誤表示しない。
                data.put("remarks", daily.getRemarks());
            }
            return new VersionSnapshot(value(row.getVersion()), data);
        }
        if ("expense".equals(command.screen())) {
            Long id = payload.has("id") && !payload.path("id").isNull() ? payload.path("id").asLong() : null;
            ExpenseRequest row = id == null ? null : expenseRequestMapper.selectByIdForUpdate(id);
            if (row == null) return new VersionSnapshot(0, Map.of("exists", false));
            if (!Objects.equals(context.engineerId(), row.getEngineerId())) {
                throw BusinessException.of(403, "error.my.notOwner");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exists", true); data.put("id", row.getId()); data.put("version", value(row.getVersion()));
            data.put("status", row.getStatus()); data.put("amount", row.getAmount());
            data.put("description", row.getDescription());
            return new VersionSnapshot(value(row.getVersion()), data);
        }
        if ("change-request".equals(command.screen())) {
            Engineer row = engineerMapper.selectByIdForUpdate(context.engineerId());
            if (row == null) throw BusinessException.of(404, "error.my.notLinked");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("exists", true); data.put("id", row.getId()); data.put("version", value(row.getVersion()));
            data.put("fullName", row.getFullName()); data.put("expectedUnitPrice", row.getExpectedUnitPrice());
            data.put("availableDate", row.getAvailableDate());
            return new VersionSnapshot(value(row.getVersion()), data);
        }
        throw BusinessException.of(400, "error.pwa.commandInvalid");
    }

    private void assertOwnedContract(Long engineerId, Long contractId) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) throw BusinessException.of(404, "error.workRecord.noContract2");
        if (!Objects.equals(engineerId, contract.getEngineerId())) {
            throw BusinessException.of(403, "error.my.notOwner");
        }
    }

    /** client/serverで同じoperation境界をhash・ledgerへ記録する。route IDも副作用対象の一部である。 */
    private String operation(String screen, String method, String uri) {
        // controller単体テスト等でrequest metadataが無い場合は旧4要素hashへフォールバックする。
        if (method == null || uri == null) return null;
        return screen + ":" + method.toUpperCase(java.util.Locale.ROOT) + ":" + uri;
    }

    private ExpenseRequestService.ExpenseDraftCommand expenseCommand(JsonNode payload) {
        return new ExpenseRequestService.ExpenseDraftCommand(
                LocalDate.parse(requiredText(payload, "expenseDate")),
                requiredText(payload, "category"),
                read(payload.path("amount"), BigDecimal.class),
                nullableLong(payload, "customerId"), nullableLong(payload, "projectId"),
                text(payload, "description", null));
    }

    /** PWA ledgerへ保存するackは、再表示に不要な本人情報・入力内容を含めない。 */
    private Map<String, Object> expenseAck(ExpenseRequestService.ExpenseRequestDto dto) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("id", dto.id());
        ack.put("status", dto.status());
        ack.put("version", value(dto.version()));
        return ack;
    }

    /** PWA ledgerへ保存する変更申請ackを最小化する。payloadJson/diffJson/reasonは保存しない。 */
    private Map<String, Object> changeRequestAck(EngineerChangeRequestService.ChangeRequestDto dto) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("id", dto.id());
        ack.put("status", dto.status());
        return ack;
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            throw BusinessException.of(400, "error.pwa.commandInvalid");
        }
    }

    private String requiredText(JsonNode node, String name) {
        String value = text(node, name, null);
        if (value == null || value.isBlank()) throw BusinessException.of(400, "error.pwa.commandInvalid");
        return value;
    }

    private String text(JsonNode node, String name, String fallback) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private Long requiredLong(JsonNode node, String name) {
        Long value = nullableLong(node, name);
        if (value == null) throw BusinessException.of(400, "error.pwa.commandInvalid");
        return value;
    }

    private Long nullableLong(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || value.isNull() ? null : value.asLong();
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    public record PwaMutationCommandBody(String screen, String month, JsonNode payload) {}

    private record VersionSnapshot(int version, Map<String, Object> data) {}
}
