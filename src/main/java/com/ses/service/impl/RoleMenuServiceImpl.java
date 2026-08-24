package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.RoleMenu;
import com.ses.mapper.RoleMenuMapper;
import com.ses.service.RoleMenuService;
import org.springframework.stereotype.Service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Menu;
import com.ses.mapper.MenuMapper;
import com.ses.service.MenuCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * ロール別メニュー権限サービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class RoleMenuServiceImpl extends ServiceImpl<RoleMenuMapper, RoleMenu> implements RoleMenuService {

    private final MenuMapper menuMapper;
    private final MenuCacheService menuCacheService;

    @Override
    public List<String> getMenuKeysByRole(String role) {
        return baseMapper.selectMenuKeysByRole(role);
    }

    @Override
    public List<String> getAllMenuKeys() {
        return baseMapper.selectAllMenuKeys();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleMenus(String role, List<Long> menuIds) {
        if (StatusConstants.ROLE_ADMIN.equals(role)) {
            throw BusinessException.of(403, "error.roleMenu.adminUnchangeable");
        }

        List<String> validRoles = List.of(
            StatusConstants.ROLE_ADMIN,
            StatusConstants.ROLE_SALES,
            StatusConstants.ROLE_HR,
            StatusConstants.ROLE_MANAGER
        );
        if (!validRoles.contains(role)) {
            throw BusinessException.of(400, "error.roleMenu.invalidRole");
        }
        
        List<Long> distinctMenuIds = menuIds != null ? new java.util.ArrayList<>(menuIds.stream().distinct().toList()) : new java.util.ArrayList<>();
        
        // MI-17: 依存関係の保護（engineer または project がある場合は skill-tag を強制追加）
        if (!distinctMenuIds.isEmpty()) {
            List<Menu> allMenus = menuMapper.selectList(null);
            Long engineerMenuId = allMenus.stream().filter(m -> "engineer".equals(m.getMenuKey())).findFirst().map(Menu::getId).orElse(null);
            Long projectMenuId = allMenus.stream().filter(m -> "project".equals(m.getMenuKey())).findFirst().map(Menu::getId).orElse(null);
            Long skillTagMenuId = allMenus.stream().filter(m -> "skill-tag".equals(m.getMenuKey())).findFirst().map(Menu::getId).orElse(null);
            
            if (skillTagMenuId != null && !distinctMenuIds.contains(skillTagMenuId)) {
                if (distinctMenuIds.contains(engineerMenuId) || distinctMenuIds.contains(projectMenuId)) {
                    distinctMenuIds.add(skillTagMenuId);
                }
            }
            Long count = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>().in(Menu::getId, distinctMenuIds)
            );
            if (count == null || count < distinctMenuIds.size()) {
                throw BusinessException.of(400, "error.roleMenu.menuNotFound");
            }
        }

        remove(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRole, role));
        if (!distinctMenuIds.isEmpty()) {
            List<RoleMenu> roleMenus = distinctMenuIds.stream()
                    .map(menuId -> RoleMenu.builder().role(role).menuId(menuId).build())
                    .toList();
            saveBatch(roleMenus);
        }
        menuCacheService.invalidate();
    }
}
