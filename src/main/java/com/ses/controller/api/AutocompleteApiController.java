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

    @GetMapping("/engineers")
    public ApiResult<List<String>> getEngineers() {
        QueryWrapper<Engineer> qw = new QueryWrapper<Engineer>().select("full_name");
        // スコープONの営業には担当要員のみ返す（全件列挙IDOR防止 / R3R-31）。
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> ids = dataScopeService.allowedEngineerIds();
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
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> ids = dataScopeService.allowedCustomerIds();
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
        if (dataScopeService.isScoped()) {
            java.util.Set<Long> ids = dataScopeService.allowedProjectIds();
            if (ids.isEmpty()) return ApiResult.success(List.of());
            qw.in("id", ids);
        }
        List<String> names = projectService.listObjs(qw, obj -> (String) obj);
        return ApiResult.success(names.stream()
                .filter(n -> n != null && !n.trim().isEmpty())
                .distinct()
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
