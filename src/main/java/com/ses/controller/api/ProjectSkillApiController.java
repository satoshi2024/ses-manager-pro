package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.project.ProjectSkillDetailDto;
import com.ses.entity.ProjectSkill;
import com.ses.service.ProjectSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/skills")
@RequiredArgsConstructor
public class ProjectSkillApiController {

    private final ProjectSkillService projectSkillService;

    @GetMapping
    public ApiResult<List<ProjectSkillDetailDto>> listDetail(@PathVariable Long projectId) {
        return ApiResult.success(projectSkillService.listDetail(projectId));
    }

    @PutMapping
    public ApiResult<Void> replaceSkills(@PathVariable Long projectId, @RequestBody List<ProjectSkill> skills) {
        projectSkillService.replaceSkills(projectId, skills);
        return ApiResult.success(null);
    }
}
