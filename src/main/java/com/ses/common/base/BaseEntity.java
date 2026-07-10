package com.ses.common.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 全エンティティの基底クラス
 * 共通フィールド（ID、作成日時、更新日時、論理削除フラグ）を定義
 */
@Data
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主キー（自動採番）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 作成日時
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新日時
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 論理削除フラグ（0: 有効, 1: 削除済み）
     */
    @TableLogic
    @TableField("deleted_flag")
    private Integer deletedFlag = 0;
}
