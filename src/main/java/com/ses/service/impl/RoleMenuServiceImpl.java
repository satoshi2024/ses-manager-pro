package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Menu;
import com.ses.entity.RoleMenu;
import com.ses.mapper.MenuMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.service.MenuCacheService;
import com.ses.service.RoleMenuService;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * ロール別メニュー権限サービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class RoleMenuServiceImpl extends ServiceImpl<RoleMenuMapper, RoleMenu> implements RoleMenuService {

    private final MenuMapper menuMapper;
    private final MenuCacheService menuCacheService;
    private final AuthorizationEpochFence authorizationEpochFence;

    @Override
    public List<String> getMenuKeysByRole(String role) {
        return baseMapper.selectMenuKeysByRole(role);
    }

    @Override
    public List<String> getAllMenuKeys() {
        return baseMapper.selectAllMenuKeys();
    }

    /**
     * READ_COMMITTED にするのは、REPEATABLE READ で全体epoch行のlockより前に通常読取をしていると、旧集合の読取が
     * lock前のsnapshotを返し得るため（W3-15 review P3-3）。外側transactionへ参加した場合も旧集合はlocking readで読む。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void replaceMenus(String role, List<Long> menuIds) {
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

        // NF01 F1（計画 §4.1、§3.4「role menu・認可config」）: 全体epoch行（lock rank 1）を最初のDB文でX lockする。
        // 旧集合の読取と置換はこのlockの保持下で行い、並行する置換やCONFIRMのreader（全体epoch行のS lock）と直列化する。
        authorizationEpochFence.lockForUpdate(AuthorizationEpochAnchor.global());
        Set<Long> beforeMenuIds = currentMenuIds(role);

        List<Long> distinctMenuIds = menuIds != null
                ? new ArrayList<>(menuIds.stream().distinct().toList())
                : new ArrayList<>();

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
        // 許可メニューの集合が変わった時だけ全体epochを1進める（同じ内容の再保存では進めない。E-4）。
        authorizationEpochFence.advanceIfChanged(AuthorizationEpochAnchor.global(),
                beforeMenuIds, new TreeSet<>(distinctMenuIds));
        // cache失効は従来どおり残す（epochの代替ではなく、画面表示を即時に反映するため。E-1）。
        menuCacheService.invalidate();
    }

    /** 全体epoch行のX lock保持下でlocking readする、指定ロールの現在の許可メニューID集合（順序に依存しない比較用）。 */
    private Set<Long> currentMenuIds(String role) {
        List<Long> ids = baseMapper.selectMenuIdsByRoleForUpdate(role);
        return ids == null ? new TreeSet<>() : ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
