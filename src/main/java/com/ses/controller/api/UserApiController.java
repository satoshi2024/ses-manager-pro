package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * ユーザーAPIコントローラー
 * ユーザーアカウントのCRUDおよびロール割当を管理する（管理者専用、SecurityConfigでアクセス制御）
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('管理者')")
public class UserApiController {

    private final SysUserService sysUserService;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;

    /**
     * ユーザー一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<com.ses.dto.user.SysUserListDto>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {

        // A7-11: PageUtils.safePage で size<=0 の全件取得と上限超過を防ぐ
        Page<SysUser> page = PageUtils.safePage(current, size);
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(username)) {
            queryWrapper.like(SysUser::getUsername, username);
        }
        if (StringUtils.hasText(role)) {
            queryWrapper.eq(SysUser::getRole, role);
        }
        if (status != null) {
            queryWrapper.eq(SysUser::getStatus, status);
        }

        queryWrapper.orderByDesc(SysUser::getId);
        Page<SysUser> result = sysUserService.page(page, queryWrapper);
        result.getRecords().forEach(u -> u.setPassword(null));

        // 要員ロールの行だけ紐付けの有無を付ける。紐付けの無い要員アカウントは
        // ログインしても何も操作できないため、一覧で気づけるようにする。
        java.util.List<Long> engineerRoleUserIds = result.getRecords().stream()
                .filter(u -> "要員".equals(u.getRole()))
                .map(SysUser::getId)
                .collect(java.util.stream.Collectors.toList());
        java.util.Set<Long> linkedUserIds = engineerAccountLinkService.findLinkedUserIds(engineerRoleUserIds);

        java.util.List<com.ses.dto.user.SysUserListDto> records = new java.util.ArrayList<>();
        for (SysUser u : result.getRecords()) {
            com.ses.dto.user.SysUserListDto dto = new com.ses.dto.user.SysUserListDto();
            org.springframework.beans.BeanUtils.copyProperties(u, dto);
            if ("要員".equals(u.getRole())) {
                dto.setEngineerLinked(linkedUserIds.contains(u.getId()));
            }
            records.add(dto);
        }

        Page<com.ses.dto.user.SysUserListDto> dtoPage =
                new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(records);
        return ApiResult.success(dtoPage);
    }

    /**
     * ユーザー詳細
     */
    @GetMapping("/{id}")
    public ApiResult<SysUser> getById(@PathVariable Long id) {
        SysUser user = sysUserService.getById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return ApiResult.success(user);
    }

    /**
     * ユーザー登録
     */
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody SysUser sysUser, Authentication authentication) {
        sysUserService.createUser(sysUser, authentication);
        return ApiResult.success(true);
    }

    /**
     * ユーザー更新
     * パスワードが空の場合は既存パスワードを維持する
     */
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody SysUser sysUser, Authentication authentication) {
        sysUserService.updateUser(id, sysUser, authentication);
        return ApiResult.success(true);
    }

    /**
     * ユーザー有効/無効切替
     */
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> updateStatus(@PathVariable Long id, @RequestParam Integer status, Authentication authentication) {
        sysUserService.updateUserStatus(id, status, authentication);
        return ApiResult.success(true);
    }

    /**
     * ユーザー削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id, Authentication authentication) {
        sysUserService.deleteUser(id, authentication);
        return ApiResult.success(true);
    }
}
