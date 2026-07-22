package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.RoleMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ロール別メニュー権限マッパー
 * t_role_menuテーブルに対するデータアクセスインターフェース
 */
public interface RoleMenuMapper extends BaseMapper<RoleMenu> {

    /**
     * 指定ロールがアクセス可能なメニューキー一覧を取得する
     *
     * @param role 権限ロール
     * @return メニューキー一覧
     */
    @Select("SELECT m.menu_key FROM t_role_menu rm " +
            "JOIN m_menu m ON m.id = rm.menu_id " +
            "WHERE rm.role = #{role}")
    List<String> selectMenuKeysByRole(@Param("role") String role);

    @Select("SELECT menu_key FROM m_menu ORDER BY sort_order ASC")
    List<String> selectAllMenuKeys();
}
