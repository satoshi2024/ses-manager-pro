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
