package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PasswordPolicyValidator;
import com.ses.config.PasswordChangeEnforcementFilter;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileApiController {
    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;

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
}
