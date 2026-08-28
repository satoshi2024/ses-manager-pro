package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.DocumentLink;
import com.ses.entity.SysUser;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetScopeService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.security.DataScopeService;
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
    private final AssetMapper assetMapper;
    private final AssetAssignmentMapper assetAssignmentMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DataScopeService dataScopeService;
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
        if ("営業".equals(user.getRole()) || "マネージャー".equals(user.getRole())) {
            Set<Long> allowed = dataScopeService.allowedEngineerIds();
            if (allowed == null) return null;
            return new ArrayList<>(allowed);
        }
        return Collections.singletonList(-1L);
    }

    @Override
    public void assertAccessibleEngineer(Long engineerId) {
        if (engineerId == null) return;
        if (hasFullAccess()) return;
        List<Long> accessible = getAccessibleEngineerIds();
        if (accessible != null && !accessible.contains(engineerId)) {
            throw new BusinessException(403, "指定された要員の資産データへのアクセス権限がありません。");
        }
    }

    @Override
    public void assertAccessibleUser(Long userId) {
        if (hasFullAccess()) return;
        SysUser user = getCurrentUser();
        if (user == null || !user.getId().equals(userId)) {
            throw new BusinessException(403, "指定されたユーザーへのアクセス権限がありません。");
        }
    }

    @Override
    public boolean isAccessible(Long assetId, String role, Long actorUserId) {
        if (assetId == null) return false;
        if ("管理者".equals(role) || "HR".equals(role)) {
            return true;
        }

        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            return false;
        }

        SysUser actor = actorUserId != null ? sysUserMapper.selectById(actorUserId) : getCurrentUser();

        // 1. 要員ロールの場合: 自身に現在貸与されている資産のみ可視
        if ("要員".equals(role)) {
            if (actor == null) return false;
            Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(actor.getId());
            if (engineerId == null) return false;

            Long activeCount = assetAssignmentMapper.selectCount(new LambdaQueryWrapper<AssetAssignment>()
                    .eq(AssetAssignment::getAssetId, assetId)
                    .eq(AssetAssignment::getAssigneeType, "ENGINEER")
                    .eq(AssetAssignment::getAssigneeId, engineerId)
                    .eq(AssetAssignment::getStatus, "ACTIVE"));
            return activeCount != null && activeCount > 0;
        }

        // 2. 営業/マネージャー ロールの場合
        if ("営業".equals(role) || "マネージャー".equals(role)) {
            List<AssetAssignment> activeAssignments = assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
                    .eq(AssetAssignment::getAssetId, assetId)
                    .eq(AssetAssignment::getStatus, "ACTIVE"));
            if (activeAssignments.isEmpty()) {
                return true; // 未貸与資産は自社内であれば閲覧可
            }
            Set<Long> allowedEngineers = dataScopeService.allowedEngineerIds();
            if (allowedEngineers == null || allowedEngineers.isEmpty()) {
                return true;
            }
            for (AssetAssignment as : activeAssignments) {
                if ("ENGINEER".equals(as.getAssigneeType()) && allowedEngineers.contains(as.getAssigneeId())) {
                    return true;
                }
            }
            return false;
        }

        return false;
    }
}
