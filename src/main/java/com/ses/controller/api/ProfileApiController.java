package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PasswordPolicyValidator;
import com.ses.config.PasswordChangeEnforcementFilter;
import com.ses.config.ProductionBootstrapMode;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileApiController {
    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

    /** bootstrap sessionの改密時に同じユーザーの他sessionを失効させる。slice等で不在ならbootstrap sessionの改密を拒否する。 */
    private PersistentSessionService persistentSessionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPersistentSessionService(PersistentSessionService persistentSessionService) {
        this.persistentSessionService = persistentSessionService;
    }

    @PutMapping("/password")
    public ApiResult<Boolean> changePassword(@Valid @RequestBody PasswordChangeRequest req,
                                             Authentication authentication,
                                             HttpServletRequest request) {
        SysUser user = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, authentication.getName()));
        if (user == null) throw BusinessException.of("error.user.notFound");
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
            throw BusinessException.of("error.password.currentInvalid");
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword()))
            throw BusinessException.of("error.password.sameAsCurrent");
        PasswordPolicyValidator.validate(req.getNewPassword());
        // 本番初回bootstrap sessionの改密では、同じユーザーの他のsession（初期パスワードで先にloginした別端末等）を
        // 先に失効させる（review P3-e）。失効できなければ改密もしない（fail-closed）。
        revokeOtherSessionsIfBootstrap(request, authentication, user.getId());
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(req.getNewPassword()));
        // 初回ログイン時のパスワード変更要求（配備時の初期admin）は、本人の変更が成功した時点で解除する。
        update.setPasswordChangeRequired(0);
        boolean success = sysUserService.updateById(update);
        if (!success) throw com.ses.common.exception.BusinessException.of(404, "error.scope.notFound");
        // 更新が成功した後でだけ、このsessionの遮断（PasswordChangeEnforcementFilter）を解除する。
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(PasswordChangeEnforcementFilter.PASSWORD_CHANGE_REQUIRED_ATTRIBUTE);
        }
        // login時点の値を持つ認証主体の要求も消す（break-glass sessionの変更経路の許可判定が参照する）。
        if (authentication.getPrincipal() instanceof com.ses.config.LoginUser loginUser
                && loginUser.getSysUser() != null) {
            loginUser.getSysUser().setPasswordChangeRequired(0);
        }
        return ApiResult.success(true);
    }

    /**
     * bootstrap session（{@link ProductionBootstrapMode}）の改密なら、同じユーザーの他の永続sessionを失効させる。
     * 失効したsessionは次の要求で {@code PersistentSessionFilter} が401にする（既存の失効の仕組み）。
     * 現在のsessionが追跡されていない（hashが無い）場合は、DB上の行は全て他sessionのものなので全件を失効させる。
     */
    private void revokeOtherSessionsIfBootstrap(HttpServletRequest request, Authentication authentication, Long userId) {
        HttpSession session = request.getSession(false);
        if (!ProductionBootstrapMode.isBootstrapSessionOf(session, authentication)) {
            return;
        }
        if (persistentSessionService == null) {
            throw new IllegalStateException("[bootstrap] 他sessionを失効できないため、bootstrap sessionの改密を中止します");
        }
        String currentHash = (String) session.getAttribute(PersistentSessionService.SESSION_HASH_ATTRIBUTE);
        if (StringUtils.hasText(currentHash)) {
            persistentSessionService.revokeOthers(userId, currentHash, BOOTSTRAP_REVOKE_REASON);
        } else {
            persistentSessionService.revokeAllForUser(userId, BOOTSTRAP_REVOKE_REASON);
        }
    }

    /** bootstrap sessionの改密で他sessionを失効させた理由（{@code t_user_session.revoke_reason}）。 */
    static final String BOOTSTRAP_REVOKE_REASON = "BOOTSTRAP_PASSWORD_CHANGED";
}
