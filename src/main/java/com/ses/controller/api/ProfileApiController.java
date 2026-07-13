package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.PasswordPolicyValidator;
import com.ses.dto.profile.PasswordChangeRequest;
import com.ses.entity.SysUser;
import com.ses.service.SysUserService;
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
                                             Authentication authentication) {
        SysUser user = sysUserService.getOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, authentication.getName()));
        if (user == null) throw new BusinessException("ユーザーが見つかりません");
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
            throw new BusinessException("現在のパスワードが正しくありません");
        if (passwordEncoder.matches(req.getNewPassword(), user.getPassword()))
            throw new BusinessException("現在と同じパスワードは設定できません");
        PasswordPolicyValidator.validate(req.getNewPassword());
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(req.getNewPassword()));
        return ApiResult.success(sysUserService.updateById(update));
    }
}
