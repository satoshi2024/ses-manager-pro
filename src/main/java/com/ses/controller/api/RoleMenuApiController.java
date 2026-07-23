package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.entity.Menu;
import com.ses.entity.RoleMenu;
import com.ses.mapper.MenuMapper;
import com.ses.service.RoleMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ロール別メニュー権限APIコントローラー
 * ロールごとにアクセス可能なメニューを設定する（管理者専用、SecurityConfigでアクセス制御）
 */
@RestController
@RequestMapping("/api/role-menus")
@RequiredArgsConstructor
public class RoleMenuApiController {

    private final RoleMenuService roleMenuService;
    private final MenuMapper menuMapper;

    /**
     * 全メニュー一覧（並び順）
     */
    @GetMapping("/menus")
    public ApiResult<List<Menu>> menus() {
        LambdaQueryWrapper<Menu> queryWrapper = new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder);
        return ApiResult.success(menuMapper.selectList(queryWrapper));
    }

    /**
     * 指定ロールにアクセス許可されているメニューキー一覧
     */
    @GetMapping
    public ApiResult<List<String>> getByRole(@RequestParam String role) {
        return ApiResult.success(roleMenuService.getMenuKeysByRole(role));
    }

    /**
     * 指定ロールのメニュー許可を置き換える
     * 全削除→再登録を1トランザクションで行い、途中失敗時に権限が消えたままにならないようにする
     */
    @PutMapping
    @Transactional
    public ApiResult<Boolean> update(@RequestParam String role, @RequestBody List<Long> menuIds) {
        if (com.ses.common.constant.StatusConstants.ROLE_ADMIN.equals(role)) {
            throw com.ses.common.exception.BusinessException.of(403, "error.roleMenu.adminUnchangeable");
        }

        List<String> validRoles = List.of(
            com.ses.common.constant.StatusConstants.ROLE_ADMIN,
            com.ses.common.constant.StatusConstants.ROLE_SALES,
            com.ses.common.constant.StatusConstants.ROLE_HR,
            com.ses.common.constant.StatusConstants.ROLE_MANAGER
        );
        if (!validRoles.contains(role)) {
            throw com.ses.common.exception.BusinessException.of(400, "error.roleMenu.invalidRole");
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
        }
        if (!distinctMenuIds.isEmpty()) {
            Long count = menuMapper.selectCount(
                new LambdaQueryWrapper<Menu>().in(Menu::getId, distinctMenuIds)
            );
            if (count == null || count < distinctMenuIds.size()) {
                throw com.ses.common.exception.BusinessException.of(400, "error.roleMenu.menuNotFound");
            }
        }

        roleMenuService.remove(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRole, role));
        if (!distinctMenuIds.isEmpty()) {
            List<RoleMenu> roleMenus = distinctMenuIds.stream()
                    .map(menuId -> RoleMenu.builder().role(role).menuId(menuId).build())
                    .toList();
            roleMenuService.saveBatch(roleMenus);
        }
        return ApiResult.success(true);
    }
}
