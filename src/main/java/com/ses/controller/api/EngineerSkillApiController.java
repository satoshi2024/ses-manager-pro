package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.EngineerSkill;
import com.ses.service.EngineerSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/api/engineers/{engineerId}/skills")
@RequiredArgsConstructor
public class EngineerSkillApiController {

    private final EngineerSkillService engineerSkillService;

    @GetMapping
    public ApiResult<List<EngineerSkillDetailDto>> listDetail(@PathVariable Long engineerId) {
        return ApiResult.success(engineerSkillService.listDetail(engineerId));
    }

    @PutMapping
    public ApiResult<Void> replaceSkills(@PathVariable Long engineerId, @RequestBody List<@Valid EngineerSkill> skills) {
        engineerSkillService.replaceSkills(engineerId, skills);
        return ApiResult.success(null);
    }
}
