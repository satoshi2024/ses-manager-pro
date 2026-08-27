package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PasswordPolicyValidator;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * システムユーザーサービス実装クラス。
 * アカウントCRUDのトランザクション境界をここに置く（Controllerには置かない）。
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final EngineerSalesMapper engineerSalesMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createUser(SysUser sysUser, Authentication authentication) {
        com.ses.common.util.EntityProtectUtil.protectForCreate(sysUser);
        if (!StringUtils.hasText(sysUser.getUsername()) || !StringUtils.hasText(sysUser.getPassword())) {
            throw BusinessException.of("error.user.credentialsRequired");
        }
        long duplicated = baseMapper.countUsernameIncludingDeleted(sysUser.getUsername(), null);
        if (duplicated > 0) {
            throw BusinessException.of("error.user.loginIdDuplicate");
        }
        PasswordPolicyValidator.validate(sysUser.getPassword());
        sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        // MI-26: 作成時は常に有効（1）に強制する
        sysUser.setStatus(1);
        boolean saved = save(sysUser);
        if (!saved) {
            throw BusinessException.of("error.user.saveFailed");
        }
        // 新規ユーザーもrole相当のdefault groupへ割当てる。割当が無いとlegacy fallback
        // 判定になり、group側で権限を編集しても効かないユーザーが増える（V64/V66のbackfillと同じ状態に保つ）。
        permissionGroupManagementService.replaceAssignments(sysUser.getId(), java.util.Set.of(), authentication);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(Long id, SysUser sysUser, Authentication authentication) {
        sysUser.setId(id);
        // 有効/無効の切替は専用エンドポイント(/{id}/status)の無効化ガードを経由させる。
        // 汎用 update で status を受け付けると S1-2 の担当残存ガードを迂回できるため無視する。
        sysUser.setStatus(null);
        if (StringUtils.hasText(sysUser.getUsername())) {
            long duplicated = baseMapper.countUsernameIncludingDeleted(sysUser.getUsername(), id);
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
        SysUser old = baseMapper.selectByIdForUpdate(id);
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (StringUtils.hasText(sysUser.getRole()) && StatusConstants.ROLE_SALES.equals(old.getRole())
                && !StatusConstants.ROLE_SALES.equals(sysUser.getRole())) {
            guardNoActiveSalesAssignments(sysUser.getId());
        }
        if (StringUtils.hasText(sysUser.getPassword())) {
            PasswordPolicyValidator.validate(sysUser.getPassword());
            sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        } else {
            sysUser.setPassword(null);
        }
        boolean roleChanged = StringUtils.hasText(sysUser.getRole())
                && !sysUser.getRole().equals(old.getRole());
        boolean success = updateById(sysUser);
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // ロール変更は営業のDataScope発動条件・組織scopeの分岐（部門責任者/一般ユーザー）を変える。
        // 進めないと、変更直後もDashboardキャッシュのTTLが切れるまで旧ロールの母集団で集計される
        // （第十四次Review P1-3）。
        if (roleChanged) {
            // V63で既存roleをdefault groupへ移行済みのため、role変更時も旧role groupを残さない。
            // 空集合は対象ユーザーの新roleに対応するdefault groupへのresetを表す。
            permissionGroupManagementService.replaceAssignments(id, java.util.Set.of(), authentication);
            invalidateScope();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long id, Integer status, Authentication authentication) {
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
        boolean success = updateById(sysUser);
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (status != 1) {
            closeOrganizationAssignments(id);
            revokeUserSessions(id, "USER_DISABLED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id, Authentication authentication) {
        guardNotSelf(id, authentication, "自分自身は削除できません");
        guardNoActiveSalesAssignments(id);
        // 紐付け中の要員アカウントは削除拒否（先に要員詳細から解除させる）。
        if (engineerAccountLinkService.isUserLinked(id)) {
            throw BusinessException.of("error.engineerAccount.linkedUserDelete");
        }
        boolean success = removeById(id);
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        closeOrganizationAssignments(id);
        revokeUserSessions(id, "USER_DELETED");
    }

    private void invalidateScope() {
        com.ses.service.security.ScopeChangeInvalidator invalidator = scopeChangeInvalidatorProvider.getIfAvailable();
        if (invalidator != null) {
            invalidator.invalidate();
        }
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

    private SysUser currentUser(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, authentication.getName()));
    }
}
