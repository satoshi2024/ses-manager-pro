package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.Project;
import com.ses.dto.project.ProjectListDto;
import com.ses.dto.project.ProjectSaveDto;
import com.ses.service.ProjectService;
import com.ses.mapper.ProjectMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.TenantOwnershipResolver;
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
    private final ProjectMapper projectMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final TenantOwnershipResolver tenantOwnershipResolver;

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
        String tenantId = currentTenant();
        java.util.Set<Long> allowedIds = effectiveCustomerIds(tenantId);
        if (allowedIds.isEmpty()) {
            return ApiResult.success(new Page<>(current, size, 0));
        }

        return ApiResult.success(projectMapper.selectPageWithNames(
                page, projectName, status, customerId, customerName, allowedIds, tenantId));
    }

    /**
     * ドロップダウン用案件一覧（軽量化）
     */
    @GetMapping("/options")
    public ApiResult<java.util.List<com.ses.dto.common.OptionDto>> getOptions(@RequestParam(required = false) Long customerId) {
        String tenantId = currentTenant();
        java.util.Set<Long> allowedProjects = effectiveProjectIds(tenantId);
        if (allowedProjects.isEmpty()) {
            return ApiResult.success(java.util.Collections.emptyList());
        }
        if (customerId != null && !effectiveCustomerIds(tenantId).contains(customerId)) {
            return ApiResult.success(java.util.Collections.emptyList());
        }
        java.util.List<com.ses.dto.common.OptionDto> options = projectMapper
                .selectByIdsForTenant(tenantId, allowedProjects).stream()
                .filter(p -> customerId == null || customerId.equals(p.getCustomerId()))
                .map(p -> new com.ses.dto.common.OptionDto(p.getId(), p.getProjectName()))
                .collect(java.util.stream.Collectors.toList());
        return ApiResult.success(options);
    }

    /**
     * 案件詳細
     */
    @GetMapping("/{id}")
    public ApiResult<Project> getById(@PathVariable Long id) {
        assertProjectVisible(id);
        Project p = tenantOwnershipResolver.selectProject(currentTenant(), id);
        if (p == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        return ApiResult.success(p);
    }

    /**
     * 案件登録
     */
    @PostMapping
    public ApiResult<ProjectSaveDto> save(@Valid @RequestBody ProjectSaveDto project) {
        assertCustomerInTenant(project.getCustomerId());
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
        if (project.getId() == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        assertProjectVisible(project.getId());
        // 変更後の顧客も現在tenant・担当スコープ内であることを検証する。
        assertCustomerInTenant(project.getCustomerId());
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
        assertProjectVisible(id);
        boolean success = projectService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        return ApiResult.success(true);
    }

    /**
     * fullAccessは組織scopeのみを bypass し、tenant ownershipは常に交差する。
     * null（=全tenant無制限）は返さない。
     */
    private java.util.Set<Long> effectiveCustomerIds(String tenantId) {
        java.util.Set<Long> ownedIds = new java.util.HashSet<>(tenantOwnershipResolver.resolveCustomerIds(tenantId));
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedCustomerIds() : null;
        if (dataIds != null) ownedIds.retainAll(dataIds);
        if (!organizationScopeService.hasFullAccess()) {
            ownedIds.retainAll(organizationScopeService.allowedCustomerIds(java.time.LocalDate.now()));
        }
        return ownedIds;
    }

    /**
     * fullAccessは組織scopeのみを bypass し、顧客経由のtenant ownershipは常に交差する。
     */
    private java.util.Set<Long> effectiveProjectIds(String tenantId) {
        java.util.Set<Long> ownedIds = new java.util.HashSet<>(tenantOwnershipResolver.resolveProjectIds(tenantId));
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedProjectIds() : null;
        if (dataIds != null) ownedIds.retainAll(dataIds);
        if (!organizationScopeService.hasFullAccess()) {
            ownedIds.retainAll(organizationScopeService.allowedProjectIds(java.time.LocalDate.now()));
        }
        return ownedIds;
    }

    private void assertProjectVisible(Long id) {
        String tenantId = currentTenant();
        if (tenantOwnershipResolver.selectProject(tenantId, id) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        if (!effectiveProjectIds(tenantId).contains(id)) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    private void assertCustomerInTenant(Long customerId) {
        if (customerId == null) return;
        if (tenantOwnershipResolver.selectCustomer(currentTenant(), customerId) == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
    }

    private String currentTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }
}
