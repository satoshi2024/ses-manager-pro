package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.service.expense.ExpenseRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 経費管理API（管理者=全件、マネージャー=組織scope配下。営業・HRは不可視）。
 * menu付与（expenseManagement）に加えて@PreAuthorizeで二重に境界を張る（design §6.2決定表）。
 */
@RestController
@RequestMapping("/api/expense-requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('管理者','マネージャー')")
public class ExpenseRequestApiController {

    private final ExpenseRequestService expenseRequestService;

    @GetMapping
    public ApiResult<Page<ExpenseRequestService.ExpenseRequestDto>> list(
            @RequestParam(required = false) String engineerName,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        return ApiResult.success(
                expenseRequestService.pageManagement(engineerName, status, current, size));
    }

    @GetMapping("/{id}")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> detail(@PathVariable Long id) {
        return ApiResult.success(expenseRequestService.detailManagement(id));
    }

    @PostMapping("/{id}/mark-paid")
    public ApiResult<ExpenseRequestService.ExpenseRequestDto> markPaid(@PathVariable Long id) {
        return ApiResult.success(expenseRequestService.markPaid(id));
    }
}
