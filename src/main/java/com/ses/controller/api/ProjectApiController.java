package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
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
        
        Page<ProjectListDto> page = new Page<>(current, size);
        
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
