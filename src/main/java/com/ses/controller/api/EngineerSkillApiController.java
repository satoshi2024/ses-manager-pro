package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.dto.skill.SkillReplaceRequest;
import com.ses.service.EngineerSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

@RestController
@RequestMapping("/api/engineers/{engineerId}/skills")
@RequiredArgsConstructor
public class EngineerSkillApiController {

    private final EngineerSkillService engineerSkillService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping
    public ApiResult<List<EngineerSkillDetailDto>> listDetail(@PathVariable Long engineerId) {
        // 親要員のスコープを検証（担当外要員のスキル読取IDOR防止 / R3R-31）。
        dataScopeService.assertAllowedEngineer(engineerId);
        return ApiResult.success(engineerSkillService.listDetail(engineerId));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('管理者','HR')")
    public ApiResult<Void> replaceSkills(@PathVariable Long engineerId, @Valid @RequestBody SkillReplaceRequest request) {
        // 親要員のスコープを検証（担当外要員のスキル書込IDOR防止 / R3R-32）。
        dataScopeService.assertAllowedEngineer(engineerId);
        engineerSkillService.replaceSkills(engineerId, request);
        return ApiResult.success(null);
    }
}
