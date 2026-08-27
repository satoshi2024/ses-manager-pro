package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.servicedesk.CustomerHealthScoreDto;
import com.ses.service.servicedesk.CustomerHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 顧客ヘルススコア管理 API (/api/customer-success/health)
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
    public ApiResult<List<CustomerHealthScoreDto>> list(
            @RequestParam(required = false) String healthStatus,
            @RequestParam(required = false) String keyword) {
        List<CustomerHealthScoreDto> list = customerHealthService.listCustomerHealthSummaries(healthStatus, keyword);
        return ApiResult.success(list);
    }

    /**
     * 指定顧客のヘルススコア詳細取得
     */
    @GetMapping("/{customerId}")
    public ApiResult<CustomerHealthScoreDto> get(@PathVariable Long customerId) {
        CustomerHealthScoreDto dto = customerHealthService.calculateCustomerHealth(customerId);
        return ApiResult.success(dto);
    }

    /**
     * 月次スナップショット生成実行
     */
    @PostMapping("/snapshots")
    public ApiResult<Void> generateSnapshot(@RequestParam(required = false) String snapshotMonth) {
        customerHealthService.generateMonthlySnapshot(snapshotMonth);
        return ApiResult.success(null);
    }
}
