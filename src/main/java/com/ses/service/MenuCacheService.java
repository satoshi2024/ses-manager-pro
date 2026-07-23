package com.ses.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.Menu;
import com.ses.mapper.MenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * メニューとロール別権限の短TTLキャッシュ。
 * A7-14: 毎リクエストのDBアクセス（最大3回）を削減するためインメモリで保持する。
 */
@Service
@RequiredArgsConstructor
public class MenuCacheService {
    
    private final ObjectProvider<MenuMapper> menuMapperProvider;
    private final ObjectProvider<RoleMenuService> roleMenuServiceProvider;

    private List<Menu> allMenus = null;
    private long allMenusExpireAt = 0;
    
    private final Map<String, CachedRoleMenus> roleMenuCache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${menu.cache.ttl-ms:60000}")
    private long ttlMs;

    public synchronized List<Menu> getAllMenus() {
        if (allMenus == null || System.currentTimeMillis() > allMenusExpireAt) {
            MenuMapper mapper = menuMapperProvider.getIfAvailable();
            if (mapper != null) {
                allMenus = mapper.selectList(new LambdaQueryWrapper<Menu>().orderByAsc(Menu::getSortOrder));
            } else {
                allMenus = List.of();
            }
            allMenusExpireAt = System.currentTimeMillis() + ttlMs;
        }
        return allMenus;
    }

    public List<String> getMenuKeysByRole(String role) {
        if (role == null) {
            return List.of();
        }
        CachedRoleMenus cached = roleMenuCache.get(role);
        if (cached == null || System.currentTimeMillis() > cached.expireAt) {
            RoleMenuService rms = roleMenuServiceProvider.getIfAvailable();
            List<String> keys = rms != null ? rms.getMenuKeysByRole(role) : List.of();
            cached = new CachedRoleMenus(keys, System.currentTimeMillis() + ttlMs);
            roleMenuCache.put(role, cached);
        }
        return cached.keys;
    }

    public List<String> getAllMenuKeys() {
        return getAllMenus().stream().map(Menu::getMenuKey).toList();
    }

    public synchronized void invalidate() {
        allMenus = null;
        roleMenuCache.clear();
    }

    private static class CachedRoleMenus {
        final List<String> keys;
        final long expireAt;
        CachedRoleMenus(List<String> keys, long expireAt) {
            this.keys = keys;
            this.expireAt = expireAt;
        }
    }
}
