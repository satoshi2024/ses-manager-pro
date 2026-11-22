package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import com.ses.service.security.AdministratorContinuityGuard;
import com.ses.service.security.epoch.ActorEpochAnchors;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * システムユーザーサービス実装クラス。
 * アカウントCRUDのトランザクション境界をここに置く（Controllerには置かない）。
 *
 * <p>NF01 F1（計画 §4.1、§2.4）: 認可列（role・status・deleted_flag・tenant_id）と権限group割当を変える書込みは、
 * 対象利用者行（actor epochのanchor、lock rank 3）を最初にX lockしてから旧値を読み、値が変わった時だけ
 * actor epochを同じtransactionで1進める。tenantはlockした行の値を使う。入れ子の
 * {@code PermissionGroupManagementService#replaceAssignments} が同じ利用者を進めても、fenceがtransaction内で
 * 重複を除くので増分は1回になる（E-4）。password・email等の非認可列の更新では進めない。</p>
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final PasswordEncoder passwordEncoder;
    private final EngineerSalesMapper engineerSalesMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final com.ses.service.security.PermissionGroupManagementService permissionGroupManagementService;
    private final AuthorizationEpochFence authorizationEpochFence;

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
     * 管理者の継続性guard（RB-ADMIN-GUARD。本番だけ判定する）。手動構築のテストでは未配線で、判定しない。
     * 本番の起動時検査（OIDC管理者・break-glass・privacy-admin）を満たさなくなる更新・状態変更・削除を拒否する。
     */
    private AdministratorContinuityGuard administratorContinuityGuard;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAdministratorContinuityGuard(AdministratorContinuityGuard administratorContinuityGuard) {
        this.administratorContinuityGuard = administratorContinuityGuard;
    }

    /**
     * 作成は管理者の人数を減らさないが、入れ子の権限group割当（rank 3 の利用者行の後に書く）より前に、guardの
     * lock（全管理者の行、rank 3 の昇順）を取って lock順をほかの利用者writerと揃える（作成した行は最大のidになる）。
     * そのためほかの利用者writerと同じく READ_COMMITTED にする。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void createUser(SysUser sysUser, Authentication authentication) {
        com.ses.common.util.EntityProtectUtil.protectForCreate(sysUser);
        if (!StringUtils.hasText(sysUser.getUsername()) || !StringUtils.hasText(sysUser.getPassword())) {
            throw BusinessException.of("error.user.credentialsRequired");
        }
        AdministratorContinuityGuard.Snapshot continuity = lockAdministratorContinuity("USER_CREATE", null);
        // NF01 F-1（計画 §11）: bodyのtenantIdは使わず、操作者のDB上のtenantで作成する。
        // 操作者を特定できなければtenantを推測せずに拒否する（既定tenantへ落とさない）。
        SysUser operator = currentUser(authentication);
        if (operator == null || !StringUtils.hasText(operator.getTenantId())) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        sysUser.setTenantId(operator.getTenantId());
        long duplicated = baseMapper.countUsernameIncludingDeleted(sysUser.getUsername(), null);
        if (duplicated > 0) {
            throw BusinessException.of("error.user.loginIdDuplicate");
        }
        PasswordPolicyValidator.validate(sysUser.getPassword());
        sysUser.setPassword(passwordEncoder.encode(sysUser.getPassword()));
        // MI-26: 作成時は常に有効（1）に強制する
        sysUser.setStatus(1);
        // 初回パスワード変更要求は配備時の初期adminと本人の変更だけが扱う。入力値は使わず列の既定値(0)にする。
        sysUser.setPasswordChangeRequired(null);
        boolean saved = save(sysUser);
        if (!saved) {
            throw BusinessException.of("error.user.saveFailed");
        }
        // 新規ユーザーもrole相当のdefault groupへ割当てる。割当が無いとlegacy fallback
        // 判定になり、group側で権限を編集しても効かないユーザーが増える（V64/V66のbackfillと同じ状態に保つ）。
        // 割当集合が空→既定groupへ変わるため、入れ子の割当置換が新規ユーザーのactor epochを1回進める。
        // 入れ子の割当置換のguardは、同じtransactionのこのguardに任せる。
        permissionGroupManagementService.replaceAssignments(sysUser.getId(), java.util.Set.of(), authentication);
        verifyAdministratorContinuity(continuity);
    }

    /**
     * role・username（break-glass管理者の照合に使う）・password等の更新。role変更は入れ子で権限groupを既定へ戻す
     * （privacy-adminの所属も外れる）。管理者の継続性guardのlock（全管理者と対象の行の昇順）を最初に取るため、
     * lock待ちの間にcommitされた変更をlock後の判定で見るよう READ_COMMITTED にする（E-3）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void updateUser(Long id, SysUser sysUser, Authentication authentication) {
        // RB-ADMIN-GUARD: 本番では全管理者と対象利用者の行を（tenant, id）昇順でX lockしてから変更前の起動時条件を取る。
        AdministratorContinuityGuard.Snapshot continuity = lockAdministratorContinuity("USER_UPDATE", id);
        // E-2: 対象利用者行（actor anchor）をX lockし（guardのlockを兼ねる）、以降の判定はlock後の値で行う。
        // role判定と更新を同一ユーザー行で直列化し、並行更新の古いrole判定も防ぐ。
        SysUser old = baseMapper.selectByIdForUpdate(id);
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        sysUser.setId(id);
        // NF01 F-1（計画 §11）: tenantは更新で変えない。bodyのtenantIdがDB上の値と異なれば拒否し、同じ値でも書き込まない。
        if (StringUtils.hasText(sysUser.getTenantId()) && !sysUser.getTenantId().equals(old.getTenantId())) {
            throw BusinessException.of(400, "error.user.tenantImmutable");
        }
        sysUser.setTenantId(null);
        // 有効/無効の切替は専用エンドポイント(/{id}/status)の無効化ガードを経由させる。
        // 汎用 update で status を受け付けると S1-2 の担当残存ガードを迂回できるため無視する。
        sysUser.setStatus(null);
        // 初回パスワード変更要求は汎用updateで変更させない（null は not_null 戦略で更新対象外）。
        sysUser.setPasswordChangeRequired(null);
        if (StringUtils.hasText(sysUser.getUsername())) {
            long duplicated = baseMapper.countUsernameIncludingDeleted(sysUser.getUsername(), id);
            if (duplicated > 0) {
                throw BusinessException.of("error.user.loginIdDuplicate");
            }
        }
        // 自分自身のロール変更を禁止（自己降格による管理者権限の喪失・ロックアウトを防ぐ）
        SysUser current = currentUser(authentication);
        if (current != null && current.getId().equals(id)
                && StringUtils.hasText(sysUser.getRole()) && !sysUser.getRole().equals(current.getRole())) {
            throw BusinessException.of("error.user.roleSelfChange");
        }
        // 営業ロールから他ロールへ変更する場合、現任担当が残っていれば拒否（先に付け替えを促す）
        if (StringUtils.hasText(sysUser.getRole()) && StatusConstants.ROLE_SALES.equals(old.getRole())
                && !StatusConstants.ROLE_SALES.equals(sysUser.getRole())) {
            guardNoActiveSalesAssignments(id);
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
            // 入口のこのメソッドでactor epochを進める。入れ子の割当置換も同じ利用者を進めるが、
            // 同一transactionの重複はfenceが除くため増分は1回になる（計画 §2.4 E-4）。
            authorizationEpochFence.advance(ActorEpochAnchors.of(old));
            // V63で既存roleをdefault groupへ移行済みのため、role変更時も旧role groupを残さない。
            // 空集合は対象ユーザーの新roleに対応するdefault groupへのresetを表す。
            permissionGroupManagementService.replaceAssignments(id, java.util.Set.of(), authentication);
            invalidateScope();
        }
        // role変更（入れ子の割当置換を含む）・username変更の後の状態で起動時条件を判定し、割るならtransactionごと拒否する。
        verifyAdministratorContinuity(continuity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void updateUserStatus(Long id, Integer status, Authentication authentication) {
        if (status == null || (status != 0 && status != 1)) {
            throw BusinessException.of(400, "error.user.invalidStatus");
        }
        guardNotSelf(id, authentication, "自分自身のステータスは変更できません");
        // RB-ADMIN-GUARD: 本番では全管理者と対象利用者の行を（tenant, id）昇順でX lockしてから変更前の起動時条件を取る。
        AdministratorContinuityGuard.Snapshot continuity = lockAdministratorContinuity("USER_STATUS", id);
        // E-2・E-3: 所属close（discoveryを伴う）を内包するためREAD_COMMITTEDで、対象利用者行を先にX lockする
        // （guardのlockを兼ねる）。
        SysUser old = baseMapper.selectByIdForUpdate(id);
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 有効(status=1)以外へ切り替える場合、現任担当が残っていれば拒否
        if (status != 1) {
            guardNoActiveSalesAssignments(id);
        }
        // status列だけを更新する（他列を古い値で書き戻さない）。
        boolean success = update(new UpdateWrapper<SysUser>().eq("id", id).set("status", status));
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        authorizationEpochFence.advanceIfChanged(ActorEpochAnchors.of(old), old.getStatus(), status);
        // 無効化で起動時条件を割るなら、所属のcloseとsessionの失効の前に拒否する（transactionごとrollback）。
        verifyAdministratorContinuity(continuity);
        if (status != 1) {
            closeOrganizationAssignments(id);
            revokeUserSessions(id, "USER_DISABLED");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void deleteUser(Long id, Authentication authentication) {
        guardNotSelf(id, authentication, "自分自身は削除できません");
        // RB-ADMIN-GUARD: 本番では全管理者と対象利用者の行を（tenant, id）昇順でX lockしてから変更前の起動時条件を取る。
        AdministratorContinuityGuard.Snapshot continuity = lockAdministratorContinuity("USER_DELETE", id);
        // E-2・E-3: 所属close（discoveryを伴う）を内包するためREAD_COMMITTEDで、対象利用者行を先にX lockする
        // （guardのlockを兼ねる）。
        SysUser old = baseMapper.selectByIdForUpdate(id);
        if (old == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        guardNoActiveSalesAssignments(id);
        // 紐付け中の要員アカウントは削除拒否（先に要員詳細から解除させる）。
        // engineer epochの増分契機に連携ユーザーの削除を加えない前提（計画 §2.2）なので、この拒否を外さないこと。
        if (engineerAccountLinkService.isUserLinked(id)) {
            throw BusinessException.of("error.engineerAccount.linkedUserDelete");
        }
        boolean success = removeById(id);
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 論理削除は主体の有効性（述語(a)）を変えるため常に進める。
        authorizationEpochFence.advance(ActorEpochAnchors.of(old));
        // 削除で起動時条件を割るなら、所属のcloseとsessionの失効の前に拒否する（transactionごとrollback）。
        verifyAdministratorContinuity(continuity);
        closeOrganizationAssignments(id);
        revokeUserSessions(id, "USER_DELETED");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(Long userId, String encodedPassword) {
        if (userId == null || !StringUtils.hasText(encodedPassword)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // password・初回変更要求だけを書く列限定の更新（認可列ではないためepochは進めない。計画 §4.1、§4.4）。
        boolean success = update(new UpdateWrapper<SysUser>()
                .eq("id", userId)
                .set("password", encodedPassword)
                .set("password_change_required", 0));
        if (!success) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private AdministratorContinuityGuard.Snapshot lockAdministratorContinuity(String operation, Long targetUserId) {
        if (administratorContinuityGuard == null) {
            return AdministratorContinuityGuard.Snapshot.inactive();
        }
        return administratorContinuityGuard.lockAndCapture(operation,
                targetUserId == null ? java.util.List.of() : java.util.List.of(targetUserId));
    }

    private void verifyAdministratorContinuity(AdministratorContinuityGuard.Snapshot continuity) {
        if (administratorContinuityGuard != null) {
            administratorContinuityGuard.verify(continuity);
        }
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
