package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;
import com.ses.service.BpPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BpPaymentApiController {

    @Autowired
    private BpPaymentService bpPaymentService;

    @GetMapping("/work-records/{id}/bp-payments")
    public ApiResult<List<BpPaymentTreeDto>> getTree(@PathVariable Long id) {
        return ApiResult.success(bpPaymentService.getTreeByWorkRecordId(id));
    }

    @PostMapping("/work-records/{id}/bp-payments")
    public ApiResult<BpPayment> addLayer(@PathVariable Long id, @RequestBody BpPayment bpPayment) {
        bpPayment.setWorkRecordId(id);
        return ApiResult.success(bpPaymentService.addLayer(bpPayment));
    }

    /**
     * BP支払階層の編集用API。
     * invoiceメニューの権限境界に含めるため /api/invoices 配下に置く。
     * 支払ステータス更新は InvoiceApiController の /api/invoices/bp-payments/{id} を使う。
     */
    @PutMapping("/invoices/bp-payments/{id}/layer")
    public ApiResult<BpPayment> updateLayer(@PathVariable Long id, @RequestBody BpPayment bpPayment) {
        return ApiResult.success(bpPaymentService.updateLayer(id, bpPayment));
    }

    @DeleteMapping("/invoices/bp-payments/{id}/layer")
    public ApiResult<?> deleteLayer(@PathVariable Long id) {
        bpPaymentService.deleteLayer(id);
        return ApiResult.success(null);
    }
}
