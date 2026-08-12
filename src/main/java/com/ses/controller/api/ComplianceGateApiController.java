package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.service.ComplianceMappingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * G2 mapping version API（Phase A step 3の第一increment）。
 * 管理者のみ（SecurityConfigで制限）。mapping_hashはcanonicalizerで計算し、client supplied hashは信頼しない。
 */
@RestController
@RequestMapping("/api/compliance-gate")
@RequiredArgsConstructor
public class ComplianceGateApiController {

    private final ComplianceMappingService complianceMappingService;

    @PostMapping("/mappings")
    public ApiResult<ComplianceMappingVersion> create(@RequestBody Map<String, Object> body) {
        ComplianceMappingVersion version = complianceMappingService.create(
                (String) body.get("mappingCode"),
                (String) body.get("mappingVersion"),
                body.get("effectiveFrom") == null ? null : LocalDate.parse((String) body.get("effectiveFrom")),
                body.get("effectiveTo") == null ? null : LocalDate.parse((String) body.get("effectiveTo")),
                castSources(body.get("sources")));
        return ApiResult.success(version);
    }

    @GetMapping("/mappings")
    public ApiResult<List<ComplianceMappingVersion>> list() {
        return ApiResult.success(complianceMappingService.list());
    }

    @GetMapping("/mappings/{id}")
    public ApiResult<ComplianceMappingVersion> getById(@PathVariable Long id) {
        return ApiResult.success(complianceMappingService.getById(id));
    }

    @PutMapping("/mappings/{id}/transition")
    public ApiResult<ComplianceMappingVersion> transition(@PathVariable Long id, @RequestParam String toStatus) {
        return ApiResult.success(complianceMappingService.transition(id, toStatus));
    }

    @SuppressWarnings("unchecked")
    private List<ComplianceMappingSourceInput> castSources(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().map(item -> {
            Map<String, Object> map = (Map<String, Object>) item;
            ComplianceMappingSourceInput input = new ComplianceMappingSourceInput();
            input.setSourceCode((String) map.get("sourceCode"));
            input.setSourceUrl((String) map.get("sourceUrl"));
            input.setSourceVersion((String) map.get("sourceVersion"));
            input.setConfirmedOn(map.get("confirmedOn") == null ? null : LocalDate.parse((String) map.get("confirmedOn")));
            input.setEffectiveFrom(map.get("effectiveFrom") == null ? null : LocalDate.parse((String) map.get("effectiveFrom")));
            input.setEffectiveTo(map.get("effectiveTo") == null ? null : LocalDate.parse((String) map.get("effectiveTo")));
            return input;
        }).toList();
    }
}
