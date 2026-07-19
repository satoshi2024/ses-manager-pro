package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.entity.WorkRecordDaily;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.entity.Engineer;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.TimesheetPdfService;
import com.ses.service.WorkRecordService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要員ポータル（マイ勤怠）API。冒頭で本人の engineerId を解決し、以降すべての読み書きを
 * その engineerId 配下に限定する（パスに engineerId を受けない=越権の余地を消す）。
 */
@RestController
@RequestMapping("/api/my/timesheet")
public class MyTimesheetApiController {

    @Autowired
    private EngineerAccountLinkService linkService;
    @Autowired
    private WorkRecordService workRecordService;
    @Autowired
    private WorkRecordMapper workRecordMapper;
    @Autowired
    private ContractMapper contractMapper;
    @Autowired
    private TimesheetPdfService timesheetPdfService;
    @Autowired
    private EngineerMapper engineerMapper;

    private Long currentEngineerId() {
        Long engineerId = linkService.findEngineerIdByUserId(SecurityUtils.currentUserId());
        if (engineerId == null) {
            throw BusinessException.of("error.my.notLinked");
        }
        return engineerId;
    }

    /** contractId が本人の担当契約かを検証する（越権防止）。 */
    private void assertOwnedContract(Long engineerId, Long contractId) {
        Contract c = contractMapper.selectById(contractId);
        if (c == null || !engineerId.equals(c.getEngineerId())) {
            throw BusinessException.of("error.my.notLinked");
        }
    }

    private void assertOwnedWorkRecord(Long engineerId, Long workRecordId) {
        WorkRecord w = workRecordService.getById(workRecordId);
        if (w == null) {
            throw BusinessException.of("error.workRecord.notFound2");
        }
        assertOwnedContract(engineerId, w.getContractId());
    }

    @GetMapping
    public ApiResult<?> myTimesheet(@RequestParam String month) {
        Long engineerId = currentEngineerId();
        String monthEnd = YearMonth.parse(month).atEndOfMonth().toString();
        List<WorkRecordGridDto> rows = workRecordMapper.selectMonthlyGridForEngineer(engineerId, month, monthEnd);
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkRecordGridDto row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contractId", row.getContractId());
            m.put("contractNo", row.getContractNo());
            m.put("projectName", row.getProjectName());
            m.put("workRecordId", row.getWorkRecordId());
            m.put("status", row.getStatus());
            Contract c = contractMapper.selectById(row.getContractId());
            if (c != null) {
                m.put("contractStartDate", c.getStartDate());
                m.put("contractEndDate", c.getEndDate());
            }
            m.put("actualHours", row.getActualHours());
            m.put("remarks", row.getRemarks());
            m.put("dailies", row.getWorkRecordId() != null
                    ? workRecordService.listDaily(row.getWorkRecordId())
                    : List.of());
            result.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("month", month);
        resp.put("rows", result);
        Engineer eng = engineerMapper.selectById(engineerId);
        if (eng != null) {
            resp.put("engineerName", eng.getFullName());
        }
        return ApiResult.success(resp);
    }

    @PostMapping("/daily")
    public ApiResult<?> saveDaily(@RequestBody @jakarta.validation.Valid DailyRequest req) {
        Long engineerId = currentEngineerId();
        assertOwnedContract(engineerId, req.getContractId());
        WorkRecordDaily daily = new WorkRecordDaily();
        daily.setWorkDate(req.getWorkDate());
        daily.setStartTime(req.getStartTime());
        daily.setEndTime(req.getEndTime());
        daily.setBreakMinutes(req.getBreakMinutes());
        daily.setRemarks(req.getRemarks());
        WorkRecord record = workRecordService.saveDaily(req.getContractId(), req.getWorkMonth(), daily);
        MyTimesheetSaveResponse resp = new MyTimesheetSaveResponse();
        resp.setId(record.getId());
        resp.setActualHours(record.getActualHours());
        resp.setStatus(record.getStatus());
        return ApiResult.success(resp);
    }

    @DeleteMapping("/daily")
    public ApiResult<?> deleteDaily(@RequestParam Long contractId,
                                    @RequestParam String workMonth,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate) {
        Long engineerId = currentEngineerId();
        assertOwnedContract(engineerId, contractId);
        workRecordService.deleteDaily(contractId, workMonth, workDate);
        return ApiResult.success(null);
    }

    @PostMapping("/submit-by-month")
    public ApiResult<?> submitByMonth(@RequestParam Long contractId, @RequestParam String workMonth) {
        Long engineerId = currentEngineerId();
        assertOwnedContract(engineerId, contractId);
        workRecordService.submitByMonth(contractId, workMonth);
        return ApiResult.success(null);
    }

    @PostMapping("/{workRecordId}/submit")
    public ApiResult<?> submit(@PathVariable Long workRecordId) {
        Long engineerId = currentEngineerId();
        assertOwnedWorkRecord(engineerId, workRecordId);
        workRecordService.submit(workRecordId);
        return ApiResult.success(null);
    }

    @GetMapping("/{workRecordId}/report.pdf")
    public ResponseEntity<byte[]> report(@PathVariable Long workRecordId) {
        Long engineerId = currentEngineerId();
        assertOwnedWorkRecord(engineerId, workRecordId);
        WorkRecord w = workRecordService.getById(workRecordId);
        if (!"提出済".equals(w.getStatus()) && !"確定".equals(w.getStatus())) {
            throw BusinessException.of("error.workRecord.submittedEdit");
        }
        byte[] bytes = timesheetPdfService.generate(workRecordId);
        String fileName = "作業報告書_" + workRecordId + ".pdf";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(bytes);
    }

    /** 日次保存リクエスト。 */
    public static class MyTimesheetSaveResponse {
        private Long id;
        private java.math.BigDecimal actualHours;
        private String status;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public java.math.BigDecimal getActualHours() { return actualHours; }
        public void setActualHours(java.math.BigDecimal actualHours) { this.actualHours = actualHours; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /** 日次保存リクエスト。 */
    public static class DailyRequest {
        @jakarta.validation.constraints.NotNull
        private Long contractId;
        @jakarta.validation.constraints.NotNull
        private String workMonth;
        @jakarta.validation.constraints.NotNull
        private LocalDate workDate;
        @jakarta.validation.constraints.NotNull
        private java.time.LocalTime startTime;
        @jakarta.validation.constraints.NotNull
        private java.time.LocalTime endTime;
        @jakarta.validation.constraints.Min(0)
        @jakarta.validation.constraints.Max(1440)
        private Integer breakMinutes;
        private String remarks;
        public Long getContractId() { return contractId; }
        public void setContractId(Long v) { this.contractId = v; }
        public String getWorkMonth() { return workMonth; }
        public void setWorkMonth(String v) { this.workMonth = v; }
        public LocalDate getWorkDate() { return workDate; }
        public void setWorkDate(LocalDate v) { this.workDate = v; }
        public java.time.LocalTime getStartTime() { return startTime; }
        public void setStartTime(java.time.LocalTime v) { this.startTime = v; }
        public java.time.LocalTime getEndTime() { return endTime; }
        public void setEndTime(java.time.LocalTime v) { this.endTime = v; }
        public Integer getBreakMinutes() { return breakMinutes; }
        public void setBreakMinutes(Integer v) { this.breakMinutes = v; }
        public String getRemarks() { return remarks; }
        public void setRemarks(String v) { this.remarks = v; }
    }
}
