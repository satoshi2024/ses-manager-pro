package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.entity.Project;
import com.ses.service.ProjectService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 案件APIコントローラー
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectApiController {

    private final ProjectService projectService;

    /**
     * 案件一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Project>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status) {
        
        Page<Project> page = new Page<>(current, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Project> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        
        if (org.springframework.util.StringUtils.hasText(projectName)) {
            queryWrapper.like(Project::getProjectName, projectName);
        }
        if (org.springframework.util.StringUtils.hasText(status)) {
            queryWrapper.eq(Project::getStatus, status);
        }
        
        queryWrapper.orderByDesc(Project::getId);
        return ApiResult.success(projectService.page(page, queryWrapper));
    }

    /**
     * 案件詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Project> getById(@PathVariable Long id) {
        return ApiResult.success(projectService.getById(id));
    }

    /**
     * 案件登録
     */
    @PostMapping
    public ApiResult<Project> save(@Valid @RequestBody Project project) {
        projectService.save(project);
        return ApiResult.success(project);
    }

    /**
     * 案件更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@Valid @RequestBody Project project) {
        return ApiResult.success(projectService.updateById(project));
    }

    /**
     * 案件削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        return ApiResult.success(projectService.removeById(id));
    }
}
