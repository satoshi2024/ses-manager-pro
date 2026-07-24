package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.reconciliation.PendingDepositDto;
import com.ses.dto.reconciliation.ReconciliationApplyRequest;
import com.ses.dto.reconciliation.ReconciliationFetchRequest;
import com.ses.dto.reconciliation.ReconciliationFetchResultDto;
import com.ses.service.billing.PaymentReconciliationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 入金消込の半自動化（FR-09）。管理者/マネージャーのみ到達可能（m_menu: reconciliation）。
 */
@RestController
@RequestMapping("/api/reconciliation")
@RequiredArgsConstructor
public class ReconciliationApiController {

    private final PaymentReconciliationService paymentReconciliationService;

    @PostMapping("/fetch")
    public ApiResult<ReconciliationFetchResultDto> fetch(
            @RequestBody(required = false) ReconciliationFetchRequest request) {
        java.time.LocalDate from = request != null ? request.getFrom() : null;
        java.time.LocalDate to = request != null ? request.getTo() : null;
        return ApiResult.success(paymentReconciliationService.fetchAndReconcile(from, to));
    }

    @GetMapping("/pending")
    public ApiResult<List<PendingDepositDto>> pending() {
        return ApiResult.success(paymentReconciliationService.listPending());
    }

    @PostMapping("/{depositId}/apply")
    public ApiResult<?> apply(@PathVariable Long depositId, @RequestBody @Valid ReconciliationApplyRequest request) {
        paymentReconciliationService.apply(depositId, request.getInvoiceId());
        return ApiResult.success(null);
    }
}
