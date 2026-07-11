package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * メニューマスタエンティティ
 * m_menuテーブルに対応するエンティティクラス
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_menu")
public class Menu implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @TableField("menu_key")
    private String menuKey;

    @TableField("menu_name")
    private String menuName;

    @TableField("path_prefix")
    private String pathPrefix;

    @TableField("sort_order")
    private Integer sortOrder;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
