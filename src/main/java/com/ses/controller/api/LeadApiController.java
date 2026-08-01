package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.crm.LeadConversionDto;
import com.ses.dto.crm.LeadConversionRequest;
import com.ses.dto.crm.LeadSaveRequest;
import com.ses.entity.Lead;
import com.ses.service.LeadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** リード管理API。重複は候補表示のみで、自動統合しない。 */
@RestController
@RequestMapping("/api/crm/leads")
@RequiredArgsConstructor
public class LeadApiController {
    private final LeadService leadService;

    @GetMapping
    public ApiResult<List<Lead>> list(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String companyName) {
        return ApiResult.success(leadService.list(status, companyName));
    }

    @GetMapping("/duplicates")
    public ApiResult<List<Lead>> duplicates(@RequestParam(required = false) String companyName,
                                            @RequestParam(required = false) String contactEmail,
                                            @RequestParam(required = false) String contactPhone,
                                            @RequestParam(required = false) Long excludeId) {
        return ApiResult.success(leadService.duplicateCandidates(companyName, contactEmail, contactPhone, excludeId));
    }

    @GetMapping("/{id}")
    public ApiResult<Lead> get(@PathVariable Long id) {
        return ApiResult.success(leadService.getVisible(id));
    }

    @PostMapping
    public ApiResult<Lead> create(@Valid @RequestBody LeadSaveRequest request) {
        return ApiResult.success(leadService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResult<Lead> update(@PathVariable Long id, @Valid @RequestBody LeadSaveRequest request) {
        return ApiResult.success(leadService.update(id, request));
    }

    @PostMapping("/{id}/convert")
    public ApiResult<LeadConversionDto> convert(@PathVariable Long id,
                                                 @RequestBody(required = false) LeadConversionRequest request) {
        return ApiResult.success(leadService.convert(id, request == null ? null : request.getVersion()));
    }
}
