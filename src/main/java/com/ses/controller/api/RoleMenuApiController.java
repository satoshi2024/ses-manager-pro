package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.entity.Menu;
import com.ses.entity.RoleMenu;
import com.ses.mapper.MenuMapper;
import com.ses.service.RoleMenuService;
import lombok.RequiredArgsConstructor;
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
     */
    @PutMapping
    public ApiResult<Boolean> update(@RequestParam String role, @RequestBody List<Long> menuIds) {
        roleMenuService.remove(new LambdaQueryWrapper<RoleMenu>().eq(RoleMenu::getRole, role));
        if (menuIds != null && !menuIds.isEmpty()) {
            List<RoleMenu> roleMenus = menuIds.stream()
                    .map(menuId -> RoleMenu.builder().role(role).menuId(menuId).build())
                    .toList();
            roleMenuService.saveBatch(roleMenus);
        }
        return ApiResult.success(true);
    }
}
