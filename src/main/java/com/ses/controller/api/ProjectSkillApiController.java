package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import com.ses.service.ProjectSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/skills")
@RequiredArgsConstructor
public class ProjectSkillApiController {

    private final ProjectSkillService projectSkillService;
    private final com.ses.service.security.DataScopeService dataScopeService;

    @GetMapping
    public ApiResult<List<ProjectSkillDetailDto>> listDetail(@PathVariable Long projectId) {
        // 親案件のスコープを検証（担当外案件のスキル読取IDOR防止 / R3R-31）。
        dataScopeService.assertAllowedProject(projectId);
        return ApiResult.success(projectSkillService.listDetail(projectId));
    }

    @PutMapping
    public ApiResult<Void> replaceSkills(@PathVariable Long projectId, @RequestBody List<@Valid ProjectSkill> skills) {
        // 親案件のスコープを検証（担当外案件のスキル書込IDOR防止 / R3R-32）。
        dataScopeService.assertAllowedProject(projectId);
        projectSkillService.replaceSkills(projectId, skills);
        return ApiResult.success(null);
    }
}
