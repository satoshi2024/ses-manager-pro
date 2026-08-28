package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetScopeService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetScopeServiceImpl implements AssetScopeService {

    private final SysUserMapper sysUserMapper;
    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;
    private final EngineerAccountLinkService engineerAccountLinkService;

    private SysUser getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        String username = auth.getName();
        return sysUserMapper.selectByUsername(username);
    }

    @Override
    public boolean hasFullAccess() {
        SysUser user = getCurrentUser();
        if (user == null) {
            return false;
        }
        return "管理者".equals(user.getRole()) || "HR".equals(user.getRole());
    }

    @Override
    public List<Long> getAccessibleEngineerIds() {
        if (hasFullAccess()) {
            return null; // 全件
        }
        SysUser user = getCurrentUser();
        if (user == null) {
            return Collections.singletonList(-1L);
        }
        if ("要員".equals(user.getRole())) {
            Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(user.getId());
            if (engineerId != null) {
                return Collections.singletonList(engineerId);
            }
            return Collections.singletonList(-1L);
        }
        // 営業・マネージャー等のスコープ
        Set<Long> ids = dataScopeService.allowedEngineerIds();
        if (ids != null && ids.isEmpty()) {
            return Collections.singletonList(-1L);
        }
        return ids != null ? new ArrayList<>(ids) : null;
    }

    @Override
    public void assertAccessibleEngineer(Long engineerId) {
        if (engineerId == null) {
            return;
        }
        if (hasFullAccess()) {
            return;
        }
        List<Long> allowed = getAccessibleEngineerIds();
        if (allowed != null && !allowed.contains(engineerId)) {
            throw new BusinessException(403, "指定された要員の資産データへのアクセス権限がありません。");
        }
    }

    @Override
    public void assertAccessibleUser(Long userId) {
        if (userId == null) {
            return;
        }
        if (hasFullAccess()) {
            return;
        }
        SysUser current = getCurrentUser();
        if (current == null || !current.getId().equals(userId)) {
            throw new BusinessException(403, "指定されたユーザーの資産データへのアクセス権限がありません。");
        }
    }
}
