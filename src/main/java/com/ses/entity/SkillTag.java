package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDateTime;

/**
 * スキルタグマスタエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_skill_tag")
public class SkillTag extends BaseEntity {

    /**
     * m_skill_tag には updated_at / deleted_flag 列が存在しないため、
     * BaseEntity 由来の共通カラムをマッピング対象外にする。
     * これを無効化しないと一覧取得・保存・削除が SQL エラーになる。
     */
    @TableField(exist = false)
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private Integer deletedFlag;

    /**
     * スキル名 (Java, React 等)
     */
    private String skillName;

    /**
     * 分類: 言語, FW, DB, クラウド, OS, ツール, その他
     */
    private String category;
}
