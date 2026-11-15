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

    /**
     * 指定ロールの現在の許可メニューIDをlocking readで読む（NF01 W3-15。全体epoch行をX lockした置換の中で、
     * 外側transactionのsnapshotに関係なく最新のcommit値を旧集合として比べるため）。
     */
    @Select("SELECT menu_id FROM t_role_menu WHERE role = #{role} FOR UPDATE")
    List<Long> selectMenuIdsByRoleForUpdate(@Param("role") String role);
}
