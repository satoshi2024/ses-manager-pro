package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PageUtils;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import com.ses.common.util.PasswordPolicyValidator;

/**
 * ユーザーAPIコントローラー
 * ユーザーアカウントのCRUDおよびロール割当を管理する（管理者専用、SecurityConfigでアクセス制御）
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserApiController {

    private final SysUserService sysUserService;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final EngineerSalesMapper engineerSalesMapper;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    private final com.ses.service.security.PermissionGroupManagementService permissionGroupManagementService;

    /** 組織所属のクローズ用。組織機能を配線しないテストスライスでも壊れないよう任意解決にする。 */
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.OrganizationService>
            organizationServiceProvider;

    /** ロール変更はDataScope（営業=isScoped()判定）を変える。既存テストスライス互換のため任意解決。 */
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.security.ScopeChangeInvalidator>
            scopeChangeInvalidatorProvider;

    /** ロール・有効性・削除変更時に対象ユーザーの全sessionを失効する。未配線のsliceでは何もしない。 */
    private final org.springframework.beans.factory.ObjectProvider<com.ses.service.security.PersistentSessionService>
            persistentSessionServiceProvider;

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
    @Transactional(rollbackFor = Exception.class)
    @PostMapping
    public ApiResult<Boolean> save(@Valid @RequestBody SysUser sysUser, Authentication authentication) {
        com.ses.common.util.EntityProtectUtil.protectForCreate(sysUser);
        if (!StringUtils.hasText(sysUser.getUsername()) || !StringUtils.hasText(sysUser.getPassword())) {
            throw BusinessException.of("error.user.credentialsRequired");
        }
        long duplicated = sysUserMapper.countUsernameIncludingDeleted(sysUser.getUsername(), null);
        if (duplicated > 0) {
            throw BusinessException.of("error.user.loginIdDuplicate");
        }
        validatePasswordPolicy(sysUser.getPassword());
        sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        // MI-26: 作成時は常に有効（1）に強制する
        sysUser.setStatus(1);
        boolean saved = sysUserService.save(sysUser);
        if (!saved) {
            throw BusinessException.of("error.user.saveFailed");
        }
        // 新規ユーザーもrole相当のdefault groupへ割当てる。割当が無いとlegacy fallback
        // 判定になり、group側で権限を編集しても効かないユーザーが増える（V64/V66のbackfillと同じ状態に保つ）。
        permissionGroupManagementService.replaceAssignments(sysUser.getId(), java.util.Set.of(), authentication);
        return ApiResult.success(true);
    }

    private void validatePasswordPolicy(String password) {
        PasswordPolicyValidator.validate(password);
    }

    /**
     * ユーザー更新
     * パスワードが空の場合は既存パスワードを維持する
     */
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/{id}")
    public ApiResult<Boolean> update(@PathVariable Long id, @Valid @RequestBody SysUser sysUser, Authentication authentication) {
        sysUser.setId(id);
        // 有効/無効の切替は専用エンドポイント(/{id}/status)の無効化ガードを経由させる。
        // 汎用 update で status を受け付けると S1-2 の担当残存ガードを迂回できるため無視する。
        sysUser.setStatus(null);
        if (StringUtils.hasText(sysUser.getUsername())) {
            long duplicated = sysUserMapper.countUsernameIncludingDeleted(sysUser.getUsername(), id);
            if (duplicated > 0) {
                throw BusinessException.of("error.user.loginIdDuplicate");
            }
        }
        // 自分自身のロール変更を禁止（自己降格による管理者権限の喪失・ロックアウトを防ぐ）
        SysUser current = currentUser(authentication);
        if (current != null && sysUser.getId() != null && current.getId().equals(sysUser.getId())
                && StringUtils.hasText(sysUser.getRole()) && !sysUser.getRole().equals(current.getRole())) {
            throw BusinessException.of("error.user.roleSelfChange");
        }
        // 営業ロールから他ロールへ変更する場合、現任担当が残っていれば拒否（先に付け替えを促す）
        // role判定と更新を同一ユーザー行で直列化し、並行更新の古いrole判定を防ぐ。
        SysUser old = sysUserMapper.selectByIdForUpdate(id);
        if (old == null) {
            throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        }
        if (StringUtils.hasText(sysUser.getRole()) && old != null && StatusConstants.ROLE_SALES.equals(old.getRole())
                && !StatusConstants.ROLE_SALES.equals(sysUser.getRole())) {
            guardNoActiveSalesAssignments(sysUser.getId());
        }
        if (StringUtils.hasText(sysUser.getPassword())) {
            validatePasswordPolicy(sysUser.getPassword());
            sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        } else {
            sysUser.setPassword(null);
        }
        boolean roleChanged = StringUtils.hasText(sysUser.getRole())
                && old != null && !sysUser.getRole().equals(old.getRole());
        boolean success = sysUserService.updateById(sysUser);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        // ロール変更は営業のDataScope発動条件・組織scopeの分岐（部門責任者/一般ユーザー）を変える。
        // 進めないと、変更直後もDashboardキャッシュのTTLが切れるまで旧ロールの母集団で集計される
        // （第十四次Review P1-3）。
        if (roleChanged) {
            // V63で既存roleをdefault groupへ移行済みのため、role変更時も旧role groupを残さない。
            // 空集合は対象ユーザーの新roleに対応するdefault groupへのresetを表す。
            permissionGroupManagementService.replaceAssignments(id, java.util.Set.of(), authentication);
            invalidateScope();
        }
        return ApiResult.success(true);
    }

    private void invalidateScope() {
        com.ses.service.security.ScopeChangeInvalidator invalidator = scopeChangeInvalidatorProvider.getIfAvailable();
        if (invalidator != null) {
            invalidator.invalidate();
        }
    }

    /**
     * ユーザー有効/無効切替
     */
    @Transactional
    @PutMapping("/{id}/status")
    public ApiResult<Boolean> updateStatus(@PathVariable Long id, @RequestParam Integer status, Authentication authentication) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.of(400, "error.user.invalidStatus");
        }
        guardNotSelf(id, authentication, "自分自身のステータスは変更できません");
        // 有効(status=1)以外へ切り替える場合、現任担当が残っていれば拒否
        if (status != 1) {
            guardNoActiveSalesAssignments(id);
        }
        SysUser sysUser = new SysUser();
        sysUser.setId(id);
        sysUser.setStatus(status);
        boolean success = sysUserService.updateById(sysUser);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        if (status != 1) {
            closeOrganizationAssignments(id);
            revokeUserSessions(id, "USER_DISABLED");
        }
        return ApiResult.success(true);
    }

    /**
     * ユーザー削除
     */
    @Transactional
    @DeleteMapping("/{id}")
    public ApiResult<Boolean> delete(@PathVariable Long id, Authentication authentication) {
        guardNotSelf(id, authentication, "自分自身は削除できません");
        guardNoActiveSalesAssignments(id);
        // 紐付け中の要員アカウントは削除拒否（先に要員詳細から解除させる）。
        if (engineerAccountLinkService.isUserLinked(id)) {
            throw BusinessException.of("error.engineerAccount.linkedUserDelete");
        }
        boolean success = sysUserService.removeById(id);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        closeOrganizationAssignments(id);
        revokeUserSessions(id, "USER_DELETED");
        return ApiResult.success(true);
    }

    private void revokeUserSessions(Long userId, String reason) {
        com.ses.service.security.PersistentSessionService sessionService =
                persistentSessionServiceProvider.getIfAvailable();
        if (sessionService != null) {
            sessionService.revokeAllForUser(userId, reason);
        }
    }

    /**
     * 退職・停止時に有効な組織所属を閉じる。
     *
     * <p>閉じないと {@code valid_to IS NULL} のまま残り、退職者が組織スコープの母集団・
     * 部門損益の帰属・上長候補に居座り続ける。所属行は履歴として残すため論理削除はしない。
     * 組織機能を配線していないテストスライスでは何もしない。
     */
    private void closeOrganizationAssignments(Long userId) {
        com.ses.service.OrganizationService organizationService = organizationServiceProvider.getIfAvailable();
        if (organizationService != null) {
            organizationService.closeAssignmentsForUser(userId, java.time.LocalDate.now());
        }
    }

    /**
     * 当該ユーザーが現任の担当営業割当（released_at IS NULL）を持つ場合は操作を拒否する。
     * 過去実績（released_at 設定済みの履歴、契約の sales_user_id）には影響しない。
     */
    private void guardNoActiveSalesAssignments(Long id) {
        long count = engineerSalesMapper.selectCount(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getSalesUserId, id)
                .isNull(EngineerSales::getReleasedAt));
        if (count > 0) {
            throw BusinessException.of("error.user.hasActiveSalesAssignments", count);
        }
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






