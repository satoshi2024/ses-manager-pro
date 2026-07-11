package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * ロール別メニュー権限エンティティ
 * t_role_menuテーブルに対応するエンティティクラス
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_role_menu")
public class RoleMenu implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String role;

    @TableField("menu_id")
    private Long menuId;
}
