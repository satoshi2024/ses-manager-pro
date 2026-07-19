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

    @GetMapping("/summary")
    public ApiResult<?> summary(@RequestParam String month) {
        return ApiResult.success(monthlyClosingService.summary(month));
    }

    @PostMapping("/confirm")
    public ApiResult<?> confirm(@RequestBody MonthRequest request) {
        monthlyClosingService.confirmClosing(request.getMonth(),
                SecurityUtils.currentUserId(), SecurityUtils.currentRole());
        return ApiResult.success(null);
    }

    @PostMapping("/reopen")
    public ApiResult<?> reopen(@RequestBody MonthRequest request) {
        monthlyClosingService.reopenClosing(request.getMonth(),
                SecurityUtils.currentUserId(), SecurityUtils.currentRole());
        return ApiResult.success(null);
    }

    public static class MonthRequest {
        private String month;
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
    }
}
