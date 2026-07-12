package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class UserApiController {

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

    /**
     * ユーザー一覧（ページネーション）
     */
    @GetMapping
    public ApiResult<Page<SysUser>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {

        Page<SysUser> page = new Page<>(current, size);
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
        return ApiResult.success(result);
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
    public ApiResult<Boolean> save(@Valid @RequestBody SysUser sysUser) {
        if (!StringUtils.hasText(sysUser.getUsername()) || !StringUtils.hasText(sysUser.getPassword())) {
            throw new BusinessException("ログインIDとパスワードは必須です");
        }
        long duplicated = sysUserService.count(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, sysUser.getUsername()));
        if (duplicated > 0) {
            throw new BusinessException("このログインIDは既に使用されています");
        }
        sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        return ApiResult.success(sysUserService.save(sysUser));
    }

    /**
     * ユーザー更新
     * パスワードが空の場合は既存パスワードを維持する
     */
    @PutMapping
    public ApiResult<Boolean> update(@Valid @RequestBody SysUser sysUser, Authentication authentication) {
        if (StringUtils.hasText(sysUser.getUsername())) {
            long duplicated = sysUserService.count(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, sysUser.getUsername())
                    .ne(sysUser.getId() != null, SysUser::getId, sysUser.getId()));
            if (duplicated > 0) {
                throw new BusinessException("このログインIDは既に使用されています");
            }
        }
        // 自分自身のロール変更を禁止（自己降格による管理者権限の喪失・ロックアウトを防ぐ）
        SysUser current = currentUser(authentication);
        if (current != null && sysUser.getId() != null && current.getId().equals(sysUser.getId())
                && StringUtils.hasText(sysUser.getRole()) && !sysUser.getRole().equals(current.getRole())) {
            throw new BusinessException("自分自身のロールは変更できません");
        }
        if (StringUtils.hasText(sysUser.getPassword())) {
            sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        } else {
            sysUser.setPassword(null);
        }
        return ApiResult.success(sysUserService.updateById(sysUser));
    }

    /**
     * ユーザー有効/無効切替
     */
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> updateStatus(@PathVariable Long id, @RequestParam Integer status, Authentication authentication) {
        guardNotSelf(id, authentication, "自分自身のステータスは変更できません");
        SysUser sysUser = new SysUser();
        sysUser.setId(id);
        sysUser.setStatus(status);
        return ApiResult.success(sysUserService.updateById(sysUser));
    }

    /**
     * ユーザー削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id, Authentication authentication) {
        guardNotSelf(id, authentication, "自分自身は削除できません");
        return ApiResult.success(sysUserService.removeById(id));
    }

    private void guardNotSelf(Long id, Authentication authentication, String message) {
        SysUser current = currentUser(authentication);
        if (current != null && current.getId().equals(id)) {
            throw new BusinessException(message);
        }
    }

    /**
     * ログイン中ユーザーのエンティティを取得する
     */
    private SysUser currentUser(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return sysUserService.getOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, authentication.getName()));
    }
}
