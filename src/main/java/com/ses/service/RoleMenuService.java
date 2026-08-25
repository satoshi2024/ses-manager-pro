package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.RoleMenu;

import java.util.List;

/**
 * ロール別メニュー権限サービスインターフェース
 */
public interface RoleMenuService extends IService<RoleMenu> {

    /**
     * 指定ロールがアクセス可能なメニューキー一覧を取得する
     *
     * @param role 権限ロール
     * @return メニューキー一覧
     */
    List<String> getMenuKeysByRole(String role);

    /**
     * マスタ内の全メニューキー一覧を取得する（管理者特権用）
     *
     * @return 全メニューキー一覧
     */
    List<String> getAllMenuKeys();

    /**
     * 指定ロールのメニュー許可を置き換える
     *
     * @param role ロール
     * @param menuIds メニューIDリスト
     */
    void updateRoleMenus(String role, List<Long> menuIds);
}
