package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.entity.PermissionGroup;
import com.ses.service.security.PermissionGroupManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/** 管理者向けpermission group割当API。 */
@RestController
@RequestMapping("/api/permission-groups")
@RequiredArgsConstructor
@org.springframework.security.access.prepost.PreAuthorize("hasRole('管理者')")
public class PermissionGroupApiController {

    private final PermissionGroupManagementService permissionGroupManagementService;

    @GetMapping
    public ApiResult<List<PermissionGroup>> list() {
        return ApiResult.success(permissionGroupManagementService.listGroups());
    }

    @GetMapping("/users/{userId}")
    public ApiResult<Set<Long>> assigned(@PathVariable Long userId) {
        return ApiResult.success(permissionGroupManagementService.assignedGroupIds(userId));
    }

    @PutMapping("/users/{userId}")
    public ApiResult<Boolean> replace(@PathVariable Long userId,
                                      @RequestBody AssignmentRequest request,
                                      Authentication authentication) {
        permissionGroupManagementService.replaceAssignments(userId,
                request == null ? Set.of() : request.groupIds(), authentication);
        return ApiResult.success(true);
    }

    public record AssignmentRequest(Set<Long> groupIds) {
    }
}
