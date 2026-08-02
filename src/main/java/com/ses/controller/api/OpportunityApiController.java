package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.dto.crm.CrmKpiDto;
import com.ses.dto.crm.OpportunityListDto;
import com.ses.dto.crm.OpportunitySaveRequest;
import com.ses.dto.crm.OpportunityStageChangeRequest;
import com.ses.entity.Opportunity;
import com.ses.service.OpportunityService;
import com.ses.service.CrmKpiService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.CrmScopeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 商機状態・変換API。CRUD画面はT051で追加する。 */
@RestController
@RequestMapping("/api/crm/opportunities")
@RequiredArgsConstructor
public class OpportunityApiController {

    private final OpportunityService opportunityService;
    private final CrmScopeService crmScopeService;
    private final CrmKpiService crmKpiService;

    @PutMapping("/{id}/stage")
    public ApiResult<Opportunity> changeStage(@PathVariable Long id,
                                               @Valid @RequestBody OpportunityStageChangeRequest request) {
        return ApiResult.success(opportunityService.changeStage(
                id, request.getStage(), request.getLostReason(), request.getVersion()));
    }

    @GetMapping
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<OpportunityListDto>> list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) Long ownerUserId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResult.success(opportunityService.pageForScreen(stage, ownerUserId, current, size));
    }

    @GetMapping("/kpi")
    public ApiResult<CrmKpiDto> kpi(@RequestParam(required = false) Long ownerUserId,
                                   @RequestParam(required = false) String stage) {
        return ApiResult.success(crmKpiService.summarize(ownerUserId, stage));
    }

    @PostMapping
    public ApiResult<Opportunity> create(@Valid @RequestBody OpportunitySaveRequest request) {
        return ApiResult.success(opportunityService.createBasic(request));
    }

    @PutMapping("/{id}")
    public ApiResult<Opportunity> update(@PathVariable Long id,
                                         @Valid @RequestBody OpportunitySaveRequest request) {
        return ApiResult.success(opportunityService.updateBasic(id, request));
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
        if (!crmScopeService.isOpportunityVisible(opportunity.getCustomerId(), opportunity.getOwnerUserId(), java.time.LocalDate.now())) {
            throw BusinessException.of(404, "error.opportunity.notFound");
        }
        return ApiResult.success(opportunity);
    }
}
