package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;
import com.ses.service.WorkRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/work-records")
@RequiredArgsConstructor
public class WorkRecordApiController {

    private final WorkRecordService workRecordService;

    @GetMapping("/grid")
    public ApiResult<List<WorkRecordGridDto>> getGrid(@RequestParam String month) {
        return ApiResult.success(workRecordService.monthlyGrid(month));
    }

    @PutMapping
    public ApiResult<WorkRecord> saveHours(@RequestBody Map<String, Object> body) {
        Long contractId = Long.valueOf(body.get("contractId").toString());
        String workMonth = body.get("workMonth").toString();
        BigDecimal actualHours = new BigDecimal(body.get("actualHours").toString());
        String remarks = body.get("remarks") != null ? body.get("remarks").toString() : null;

        return ApiResult.success(workRecordService.saveHours(contractId, workMonth, actualHours, remarks));
    }

    @PostMapping("/confirm")
    public ApiResult<Void> confirmMonth(@RequestParam String month) {
        workRecordService.confirmMonth(month);
        return ApiResult.success(null);
    }

    /**
     * 月次確定の解除（管理者のみ）。
     * SecurityConfig の requestMatchers でも管理者に制限しており二重防御。
     */
    @PostMapping("/reopen")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Void> reopenMonth(@RequestParam String month) {
        workRecordService.reopenMonth(month);
        return ApiResult.success(null);
    }
}
