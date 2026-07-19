package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 要員アカウント紐付けAPI（要員詳細のログインアカウントカード用）。engineer メニュー配下。
 */
@RestController
@RequestMapping("/api/engineers/{engineerId}/account-link")
@PreAuthorize("hasAnyRole('管理者','HR')")
public class EngineerAccountLinkApiController {

    @Autowired
    private EngineerAccountLinkService linkService;
    @Autowired
    private SysUserMapper sysUserMapper;

    /** 現在の紐付け（未紐付けは null）。 */
    @GetMapping
    public ApiResult<?> current(@PathVariable Long engineerId) {
        return ApiResult.success(linkService.findByEngineerId(engineerId));
    }

    /** 紐付け候補（role=要員・未紐付け・有効）。 */
    @GetMapping("/candidates")
    public ApiResult<?> candidates(@PathVariable Long engineerId) {
        List<SysUser> engineers = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                .eq("role", "要員")
                .eq("status", 1));
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysUser u : engineers) {
            if (!linkService.isUserLinked(u.getId())) {
                result.add(Map.of("id", u.getId(), "username", u.getUsername(),
                        "realName", u.getRealName() != null ? u.getRealName() : ""));
            }
        }
        return ApiResult.success(result);
    }

    @PostMapping
    public ApiResult<?> link(@PathVariable Long engineerId, @RequestBody LinkRequest req) {
        return ApiResult.success(linkService.link(engineerId, req.getSysUserId(), SecurityUtils.currentUserId()));
    }

    @DeleteMapping
    public ApiResult<?> unlink(@PathVariable Long engineerId) {
        linkService.unlinkByEngineerId(engineerId);
        return ApiResult.success(null);
    }

    public static class LinkRequest {
        private Long sysUserId;
        public Long getSysUserId() { return sysUserId; }
        public void setSysUserId(Long sysUserId) { this.sysUserId = sysUserId; }
    }
}
