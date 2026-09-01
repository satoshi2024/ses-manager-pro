package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.service.servicedesk.CustomerHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 顧客ヘルススコア・スナップショット API コントローラー
 */
@RestController
@RequestMapping({"/api/customer-success/health", "/api/service-desk/health"})
@RequiredArgsConstructor
public class CustomerHealthApiController {

    private final CustomerHealthService customerHealthService;

    /**
     * 顧客ヘルススコア一覧取得
     */
    @GetMapping
    public ApiResult<List<CustomerHealthScoreDto>> listHealthSummaries(
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String keyword) {
        return ApiResult.success(customerHealthService.listCustomerHealthSummaries(healthStatus, keyword));
    }

    /**
     * 顧客ヘルススコア詳細取得
     */
    @GetMapping("/{customerId}")
    public ApiResult<CustomerHealthScoreDto> getCustomerHealth(@PathVariable Long customerId) {
        return ApiResult.success(customerHealthService.calculateCustomerHealth(customerId));
    }

    /**
     * 月次スナップショット手動生成実行（管理者専用）
     */
    @PostMapping("/snapshots")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<Map<String, Object>> generateMonthlySnapshot(
            @RequestParam(required = false) String targetMonth,
            @RequestParam(required = false) String reason) {
        customerHealthService.generateMonthlySnapshot(targetMonth, reason);
        return ApiResult.success(Map.of("message", "顧客ヘルススナップショットの生成が完了しました"));
    }
}
