package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.SysUser;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.ProjectService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/autocomplete")
@RequiredArgsConstructor
public class AutocompleteApiController {

    private final EngineerService engineerService;
    private final CustomerService customerService;
    private final ProjectService projectService;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.CostCenterService costCenterService;
    private final com.ses.service.security.TenantOwnershipResolver tenantOwnershipResolver;
    private final com.ses.mapper.ProjectMapper projectMapper;

    @GetMapping("/engineers")
    public ApiResult<List<String>> getEngineers() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> ids = effectiveEngineerIds();
        if (ids.isEmpty()) return ApiResult.success(List.of());
        List<String> names = tenantOwnershipResolver.selectEngineers(tenantId, ids, null, null, null).stream()
                .map(Engineer::getFullName)
                .toList();
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    @GetMapping("/customers")
    public ApiResult<List<String>> getCustomers() {
        java.util.Set<Long> ids = effectiveCustomerIds();
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        List<String> names = tenantOwnershipResolver.selectCustomers(tenantId, ids, null).stream()
                .map(Customer::getCompanyName)
                .toList();
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    @GetMapping("/projects")
    public ApiResult<List<String>> getProjects() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> ids = effectiveProjectIds();
        if (ids.isEmpty()) return ApiResult.success(List.of());
        List<String> names = projectMapperForTenant(tenantId, ids).stream()
                .map(Project::getProjectName).toList();
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    private java.util.Set<Long> effectiveEngineerIds() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> owned = new java.util.HashSet<>(tenantOwnershipResolver.resolveEngineerIds(tenantId));
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedEngineerIds() : null;
        if (dataIds != null) owned.retainAll(dataIds);
        if (!organizationScopeService.hasFullAccess()) {
            owned.retainAll(organizationScopeService.allowedEngineerIds(java.time.LocalDate.now()));
        }
        return owned;
    }

    private java.util.Set<Long> effectiveCustomerIds() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> ownedIds = new java.util.HashSet<>(tenantOwnershipResolver.resolveCustomerIds(tenantId));
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedCustomerIds() : null;
        if (dataIds != null) ownedIds.retainAll(dataIds);
        if (organizationScopeService.hasFullAccess()) {
            return ownedIds;
        }
        java.util.Set<Long> scoped = organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedCustomerIds(java.time.LocalDate.now()), dataIds);
        if (scoped == null) return ownedIds;
        ownedIds.retainAll(scoped);
        return ownedIds;
    }

    private java.util.Set<Long> effectiveProjectIds() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedProjectIds() : null;
        java.util.Set<Long> scoped = organizationScopeService.hasFullAccess() ? null
                : organizationScopeService.allowedProjectIds(java.time.LocalDate.now());
        java.util.Set<Long> ids = new java.util.HashSet<>(tenantOwnershipResolver.resolveProjectIds(tenantId));
        if (dataIds != null) ids.retainAll(dataIds);
        if (scoped != null) ids.retainAll(scoped);
        return ids;
    }

    private java.util.List<Project> projectMapperForTenant(String tenantId, java.util.Set<Long> ids) {
        return projectMapper.selectByIdsForTenant(tenantId, ids);
    }

    /**
     * 顧客の選択肢（id + 表示名）。管理会計フィルターのような<select>用。
     *
     * <p>{@link #getCustomers()} は既存の自由入力{@code <datalist>}（顧客/案件一覧の検索欄）向けに
     * 会社名の文字列配列を返す契約が固定されており、そちらを壊さずに済ませるため別エンドポイントにする。
     * <select>側は選択肢の{@code value}にIDを必要とするため、文字列配列を渡すと
     * {@code option value="undefined"} になり、顧客/案件でのID絞り込みが機能しない
     * （第十四次Review P1-6）。
     */
    @GetMapping("/customer-options")
    public ApiResult<List<com.ses.dto.common.OptionDto>> getCustomerOptions() {
        java.util.Set<Long> ids = effectiveCustomerIds();
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        return ApiResult.success(tenantOwnershipResolver.selectCustomers(tenantId, ids, null).stream()
                .map(c -> new com.ses.dto.common.OptionDto(c.getId(), c.getCompanyName()))
                .sorted(java.util.Comparator.comparing(com.ses.dto.common.OptionDto::getId))
                .collect(Collectors.toList()));
    }

    /** 案件の選択肢（id + 表示名）。{@link #getCustomerOptions()} と同じ理由で自由入力用とは別に持つ。 */
    @GetMapping("/project-options")
    public ApiResult<List<com.ses.dto.common.OptionDto>> getProjectOptions() {
        java.util.Set<Long> ids = effectiveProjectIds();
        if (ids.isEmpty()) return ApiResult.success(List.of());
        return ApiResult.success(projectMapper.selectByIdsForTenant(
                        AccountingTenantContextHolder.requireTenantContext(), ids).stream()
                .map(p -> new com.ses.dto.common.OptionDto(p.getId(), p.getProjectName()))
                .collect(Collectors.toList()));
    }

    /**
     * 組織の選択肢。要員・契約・管理会計の各フォームがID直打ちにならないように共有する。
     * 返すのは id/code/name だけで、組織scopeを適用する（部門責任者は自組織と子孫のみ）。
     */
    @GetMapping("/organizations")
    public ApiResult<List<com.ses.dto.organization.OrganizationOptionDto>> getOrganizations() {
        return ApiResult.success(organizationScopeService.listVisibleOrganizations(null, java.time.LocalDate.now())
                .stream()
                .map(unit -> new com.ses.dto.organization.OrganizationOptionDto(
                        unit.getId(), unit.getCode(), unit.getName(), unit.getParentId()))
                .collect(Collectors.toList()));
    }

    /**
     * 法人の選択肢。管理会計・組織一覧の法人フィルターがID直打ちにならないように共有する。
     *
     * <p>法人マスタは未導入で {@code m_organization_unit.legal_entity_id} が唯一の法人識別子のため、
     * 表示名はscope内で見える組織から代表1件（ルート優先）の組織名を借用する。代表組織が
     * scope外で見えない場合のみ「法人#ID」に留める（第十四次Review P2-1）。
     */
    @GetMapping("/legal-entities")
    public ApiResult<List<com.ses.dto.common.OptionDto>> getLegalEntities() {
        List<com.ses.entity.OrganizationUnit> visible =
                organizationScopeService.listVisibleOrganizations(null, java.time.LocalDate.now());
        java.util.Map<Long, com.ses.entity.OrganizationUnit> representative = new java.util.LinkedHashMap<>();
        for (com.ses.entity.OrganizationUnit unit : visible) {
            if (unit.getLegalEntityId() == null) {
                continue;
            }
            com.ses.entity.OrganizationUnit current = representative.get(unit.getLegalEntityId());
            // ルート組織(parentIdなし)を優先して法人の代表名にする。
            if (current == null || (current.getParentId() != null && unit.getParentId() == null)) {
                representative.put(unit.getLegalEntityId(), unit);
            }
        }
        return ApiResult.success(representative.entrySet().stream()
                .map(entry -> new com.ses.dto.common.OptionDto(entry.getKey(), entry.getValue().getName()))
                .sorted(java.util.Comparator.comparing(com.ses.dto.common.OptionDto::getId))
                .collect(Collectors.toList()));
    }

    /** 原価部門の選択肢。組織scopeを適用する。 */
    @GetMapping("/cost-centers")
    public ApiResult<List<com.ses.dto.organization.OrganizationOptionDto>> getCostCenters() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.CostCenter> query =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.ses.entity.CostCenter>()
                        .eq(com.ses.entity.CostCenter::getStatus, "有効")
                        .orderByAsc(com.ses.entity.CostCenter::getCode);
        organizationScopeService.applyOrganizationScope(query, com.ses.entity.CostCenter::getOrganizationId);
        return ApiResult.success(costCenterService.list(query).stream()
                .map(center -> new com.ses.dto.organization.OrganizationOptionDto(
                        center.getId(), center.getCode(), center.getName(), center.getOrganizationId()))
                .collect(Collectors.toList()));
    }

    /**
     * 所属登録の宛先ユーザー候補。組織管理画面の所属タブから使う。
     * {@code /api/users} は管理者専用のため、HR・部門責任者向けに公開項目を絞った専用DTOで返す。
     */
    @GetMapping("/assignable-users")
    public ApiResult<List<com.ses.dto.organization.AssignableUserDto>> getAssignableUsers() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        List<SysUser> users = sysUserMapper.selectByTenant(tenantId).stream()
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .toList();
        return ApiResult.success(users.stream()
                .filter(user -> organizationScopeService.hasFullAccess()
                        || organizationScopeService.isAllowedUser(user.getId(), java.time.LocalDate.now()))
                .map(user -> new com.ses.dto.organization.AssignableUserDto(
                        user.getId(), user.getUsername(), user.getRealName(), user.getRole()))
                .collect(Collectors.toList()));
    }

    /**
     * 営業担当の選択肢。管理会計の営業別絞り込みがID直打ちにならないように共有する。
     *
     * <p>組織scopeで絞らないのは、営業は組織を跨いで契約を担当するのが通常運用のため
     * （design.md「組織scopeとDataScopeの結合規則」）。実データの可視範囲はsummary側の
     * SQL境界で担保されるので、ここで見えない担当者名を選んでも件数が増えることはない。
     */
    @GetMapping("/sales-users")
    public ApiResult<List<com.ses.dto.organization.AssignableUserDto>> getSalesUsers() {
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        List<SysUser> users = sysUserMapper.selectActiveByRoleAndTenant("営業", tenantId);
        return ApiResult.success(users.stream()
                .map(user -> new com.ses.dto.organization.AssignableUserDto(
                        user.getId(), user.getUsername(), user.getRealName(), user.getRole()))
                .collect(Collectors.toList()));
    }

    /** ログインユーザー一覧オートコンプリート。管理者のみ利用可。 */
    @GetMapping("/users")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<List<String>> getUsers() {
        List<String> names = sysUserMapper.selectUsernamesByTenant(
                AccountingTenantContextHolder.requireTenantContext());
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }
}
