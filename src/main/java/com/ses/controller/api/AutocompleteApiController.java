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
import com.ses.service.SysUserService;
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
    private final SysUserService sysUserService;
    private final com.ses.service.security.DataScopeService dataScopeService;
    private final com.ses.service.security.OrganizationScopeService organizationScopeService;
    private final com.ses.service.CostCenterService costCenterService;

    @GetMapping("/engineers")
    public ApiResult<List<String>> getEngineers() {
        QueryWrapper<Engineer> qw = new QueryWrapper<Engineer>().select("full_name");
        // スコープONの営業には担当要員のみ返す（全件列挙IDOR防止 / R3R-31）。
        java.util.Set<Long> ids = effectiveEngineerIds();
        if (ids != null) {
            if (ids.isEmpty()) return ApiResult.success(List.of());
            qw.in("id", ids);
        }
        List<String> names = engineerService.listObjs(qw, obj -> (String) obj);
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    @GetMapping("/customers")
    public ApiResult<List<String>> getCustomers() {
        QueryWrapper<Customer> qw = new QueryWrapper<Customer>().select("company_name");
        java.util.Set<Long> ids = effectiveCustomerIds();
        if (ids != null) {
            if (ids.isEmpty()) return ApiResult.success(List.of());
            qw.in("id", ids);
        }
        List<String> names = customerService.listObjs(qw, obj -> (String) obj);
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    @GetMapping("/projects")
    public ApiResult<List<String>> getProjects() {
        QueryWrapper<Project> qw = new QueryWrapper<Project>().select("project_name");
        java.util.Set<Long> ids = effectiveProjectIds();
        if (ids != null) {
            if (ids.isEmpty()) return ApiResult.success(List.of());
            qw.in("id", ids);
        }
        List<String> names = projectService.listObjs(qw, obj -> (String) obj);
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }

    private java.util.Set<Long> effectiveEngineerIds() {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedEngineerIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedEngineerIds(java.time.LocalDate.now()), dataIds);
    }

    private java.util.Set<Long> effectiveCustomerIds() {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedCustomerIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedCustomerIds(java.time.LocalDate.now()), dataIds);
    }

    private java.util.Set<Long> effectiveProjectIds() {
        java.util.Set<Long> dataIds = dataScopeService.isScoped()
                ? dataScopeService.allowedProjectIds() : null;
        if (organizationScopeService.hasFullAccess()) {
            return dataIds == null ? null : new java.util.HashSet<>(dataIds);
        }
        return organizationScopeService.intersectWithDataScope(
                organizationScopeService.allowedProjectIds(java.time.LocalDate.now()), dataIds);
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
        List<SysUser> users = sysUserService.list(new QueryWrapper<SysUser>()
                .select("id", "username", "real_name", "role")
                .eq("status", 1)
                .orderByAsc("id"));
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
        List<SysUser> users = sysUserService.list(new QueryWrapper<SysUser>()
                .select("id", "username", "real_name", "role")
                .eq("status", 1)
                .eq("role", "営業")
                .orderByAsc("id"));
        return ApiResult.success(users.stream()
                .map(user -> new com.ses.dto.organization.AssignableUserDto(
                        user.getId(), user.getUsername(), user.getRealName(), user.getRole()))
                .collect(Collectors.toList()));
    }

    /** ログインユーザー一覧オートコンプリート。管理者のみ利用可。 */
    @GetMapping("/users")
    @PreAuthorize("hasRole('管理者')")
    public ApiResult<List<String>> getUsers() {
        List<String> names = sysUserService.listObjs(
                new QueryWrapper<SysUser>().select("username"),
                obj -> (String) obj
        );
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
                .collect(Collectors.toList()));
    }
}
