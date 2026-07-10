package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

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
     * スキル名 (Java, React 等)
     */
    private String skillName;

    /**
     * 分類: 言語, FW, DB, クラウド, OS, ツール, その他
     */
    private String category;
}
