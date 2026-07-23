package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.Project;
import com.ses.dto.project.ProjectListDto;
import com.ses.dto.project.ProjectSaveDto;
import com.ses.service.ProjectService;
import com.ses.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Collection;

/**
 * 案件APIコントローラー
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectApiController {

    private final ProjectService projectService;
    private final ProjectMapper projectMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;

    /**
     * 案件一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<ProjectListDto>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerName) {
        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ（旧 defaultSize 1000 はそのまま引き継ぐ）
        Page<ProjectListDto> page = PageUtils.safePage(current, size, 1000L);
        
        Collection<Long> allowedIds = null;
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(new Page<>(current, size, 0));
            }
            allowedIds = allowed;
        }

        return ApiResult.success(projectMapper.selectPageWithNames(page, projectName, status, customerId, customerName, allowedIds));
    }

    /**
     * ドロップダウン用案件一覧（軽量化）
     */
    @GetMapping("/options")
    public ApiResult<java.util.List<com.ses.dto.common.OptionDto>> getOptions(@RequestParam(required = false) Long customerId) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Project> queryWrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (customerId != null) {
            queryWrapper.eq(Project::getCustomerId, customerId);
        }
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed.isEmpty()) {
                return ApiResult.success(java.util.Collections.emptyList());
            }
            queryWrapper.in(Project::getCustomerId, allowed);
        }
        queryWrapper.select(Project::getId, Project::getProjectName)
                    .orderByDesc(Project::getId);
        java.util.List<com.ses.dto.common.OptionDto> options = projectService.list(queryWrapper).stream()
                .map(p -> new com.ses.dto.common.OptionDto(p.getId(), p.getProjectName()))
                .collect(java.util.stream.Collectors.toList());
        return ApiResult.success(options);
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
    public ApiResult<ProjectSaveDto> save(@Valid @RequestBody ProjectSaveDto project) {
        if (project.getCustomerId() != null) {
            dataScopeService.assertAllowedCustomer(project.getCustomerId());
        }
        projectService.saveProjectWithSkills(project);
        return ApiResult.success(project);
    }

    /**
     * 案件更新
     */
    @PutMapping
    public ApiResult<Boolean> update(@Valid @RequestBody ProjectSaveDto project) {
        // 先にDB上の既存案件を認可する（担当外案件を自分の顧客へ付け替えるIDOR防止 / R3R-32）。
        if (project.getId() != null) {
            Project existing = projectService.getById(project.getId());
            if (existing == null) {
                throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
            }
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }
        // 変更後の顧客も担当スコープ内であることを検証する。
        if (project.getCustomerId() != null) {
            dataScopeService.assertAllowedCustomer(project.getCustomerId());
        }
        return ApiResult.success(projectService.updateProjectWithSkills(project));
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
        boolean success = projectService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }
}
