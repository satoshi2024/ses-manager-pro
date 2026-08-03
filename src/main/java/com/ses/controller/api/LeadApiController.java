package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.crm.LeadConversionDto;
import com.ses.dto.crm.LeadConversionRequest;
import com.ses.dto.crm.LeadSaveRequest;
import com.ses.entity.Lead;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.LeadService;
import com.ses.service.security.CrmScopeService;
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
    private final SysUserMapper sysUserMapper;
    private final CrmScopeService crmScopeService;

    @GetMapping
    public ApiResult<com.baomidou.mybatisplus.extension.plugins.pagination.Page<Lead>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String companyName,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "50") long size) {
        return ApiResult.success(leadService.page(status, companyName, current, size));
    }

    @GetMapping("/assignees")
    public ApiResult<List<com.ses.dto.common.OptionDto>> assignees() {
        var query = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, "営業").eq(SysUser::getStatus, 1).orderByAsc(SysUser::getId);
        if (!crmScopeService.hasFullAccess()) {
            var ids = crmScopeService.allowedOwnerIds(java.time.LocalDate.now());
            if (ids.isEmpty()) return ApiResult.success(List.of());
            query.in(SysUser::getId, ids);
        }
        return ApiResult.success(sysUserMapper.selectList(query).stream()
                .map(u -> new com.ses.dto.common.OptionDto(u.getId(), u.getRealName()))
                .toList());
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
