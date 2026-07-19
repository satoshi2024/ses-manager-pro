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
    private final com.ses.service.security.DataScopeService dataScopeService;

    /**
     * 案件一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<Project>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId) {
        
        Page<Project> page = new Page<>(current, size);
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Project> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(new Page<>(current, size, 0));
            }
            queryWrapper.in(Project::getCustomerId, allowed);
        }

        if (org.springframework.util.StringUtils.hasText(projectName)) {
            queryWrapper.like(Project::getProjectName, projectName);
        }
        if (org.springframework.util.StringUtils.hasText(status)) {
            queryWrapper.eq(Project::getStatus, status);
        }
        if (customerId != null) {
            queryWrapper.eq(Project::getCustomerId, customerId);
        }
        
        queryWrapper.orderByDesc(Project::getId);
        return ApiResult.success(projectService.page(page, queryWrapper));
    }

    /**
     * 案件詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Project> getById(@PathVariable Long id) {
        Project p = projectService.getById(id);
        if (p != null) {
            dataScopeService.assertAllowedCustomer(p.getCustomerId());
        }
        return ApiResult.success(p);
    }

    /**
     * 案件登録
     */
    @PostMapping
    public ApiResult<Project> save(@Valid @RequestBody Project project) {
        if (project.getCustomerId() != null) {
            dataScopeService.assertAllowedCustomer(project.getCustomerId());
        }
        projectService.save(project);
        return ApiResult.success(project);
    }

    /**
     * 案件更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@Valid @RequestBody Project project) {
        if (project.getCustomerId() != null) {
            dataScopeService.assertAllowedCustomer(project.getCustomerId());
        }
        return ApiResult.success(projectService.updateById(project));
    }

    /**
     * 案件削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id) {
        Project p = projectService.getById(id);
        if (p != null) {
            dataScopeService.assertAllowedCustomer(p.getCustomerId());
        }
        return ApiResult.success(projectService.removeById(id));
    }
}
