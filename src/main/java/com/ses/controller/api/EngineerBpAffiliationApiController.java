package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.EngineerBpAffiliation;
import com.ses.service.EngineerBpAffiliationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/bp-affiliations")
@RequiredArgsConstructor
public class EngineerBpAffiliationApiController {

    private final EngineerBpAffiliationService affiliationService;

    @PostMapping
    public ApiResult<EngineerBpAffiliation> assignAffiliation(@RequestBody AffiliationReq req) {
        EngineerBpAffiliation affiliation = affiliationService.assignBpAffiliation(
                req.getEngineerId(), req.getBpCompanyId(), req.getValidFrom(), req.getValidTo());
        return ApiResult.success(affiliation);
    }

    @GetMapping("/engineer/{engineerId}")
    public ApiResult<List<EngineerBpAffiliation>> getAffiliationHistory(@PathVariable Long engineerId) {
        return ApiResult.success(affiliationService.getAffiliationHistory(engineerId));
    }

    @GetMapping("/engineer/{engineerId}/active")
    public ApiResult<EngineerBpAffiliation> getActiveAffiliation(
            @PathVariable Long engineerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        LocalDate date = targetDate != null ? targetDate : LocalDate.now();
        return ApiResult.success(affiliationService.getActiveAffiliationAsOf(engineerId, date));
    }

    @Data
    public static class AffiliationReq {
        private Long engineerId;
        private Long bpCompanyId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate validFrom;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate validTo;
    }
}
