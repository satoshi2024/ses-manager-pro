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

    @PutMapping("/bp-payments/{id}")
    public ApiResult<BpPayment> updateLayer(@PathVariable Long id, @RequestBody BpPayment bpPayment) {
        return ApiResult.success(bpPaymentService.updateLayer(id, bpPayment));
    }

    @DeleteMapping("/bp-payments/{id}")
    public ApiResult<?> deleteLayer(@PathVariable Long id) {
        bpPaymentService.deleteLayer(id);
        return ApiResult.success(null);
    }
}
