package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.dto.crm.OpportunityStageChangeRequest;
import com.ses.entity.Opportunity;
import com.ses.service.OpportunityService;
import com.ses.service.security.DataScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 商機状態・変換API。CRUD画面はT051で追加する。 */
@RestController
@RequestMapping("/api/crm/opportunities")
@RequiredArgsConstructor
public class OpportunityApiController {

    private final OpportunityService opportunityService;
    private final DataScopeService dataScopeService;

    @PutMapping("/{id}/stage")
    public ApiResult<Opportunity> changeStage(@PathVariable Long id,
                                               @Valid @RequestBody OpportunityStageChangeRequest request) {
        return ApiResult.success(opportunityService.changeStage(
                id, request.getStage(), request.getLostReason(), request.getVersion()));
    }

    @PostMapping("/{id}/convert")
    public ApiResult<OpportunityConversionDto> convert(@PathVariable Long id) {
        return ApiResult.success(opportunityService.convertToProjectAndQuotation(id));
    }

    @GetMapping("/{id}")
    public ApiResult<Opportunity> get(@PathVariable Long id) {
        Opportunity opportunity = opportunityService.getById(id);
        if (opportunity == null) {
            throw BusinessException.of(404, "error.opportunity.notFound");
        }
        dataScopeService.assertAllowedCustomer(opportunity.getCustomerId());
        return ApiResult.success(opportunity);
    }
}
