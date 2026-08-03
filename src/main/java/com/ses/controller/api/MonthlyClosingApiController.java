package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.service.MonthlyClosingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 月次締めチェックリストAPI。
 */
@RestController
@RequestMapping("/api/monthly-closing")
public class MonthlyClosingApiController {

    @Autowired
    private MonthlyClosingService monthlyClosingService;

    @Autowired
    private com.ses.service.approval.ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;

    @GetMapping("/summary")
    public ApiResult<?> summary(@RequestParam String month) {
        return ApiResult.success(monthlyClosingService.summary(month));
    }

    @PostMapping("/confirm")
    public ApiResult<?> confirm(@RequestBody MonthRequest request) {
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<>(); command.put("operation", "confirm"); command.put("month", request.getMonth());
        return ApiResult.success(approvalTargetAdapterRegistry.request("closing.confirm", "MONTHLY_CLOSING", null, command));
    }

    @PostMapping("/reopen")
    public ApiResult<?> reopen(@RequestBody MonthRequest request) {
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<>(); command.put("operation", "reopen"); command.put("month", request.getMonth());
        return ApiResult.success(approvalTargetAdapterRegistry.request("closing.reopen", "MONTHLY_CLOSING", null, command));
    }

    public static class MonthRequest {
        private String month;
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
    }
}
