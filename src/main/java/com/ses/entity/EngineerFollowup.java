package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 要員フォロー記録エンティティ（1on1/面談/連絡）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_engineer_followup")
public class EngineerFollowup extends BaseEntity {

    /** 要員ID */
    private Long engineerId;

    /** フォロー種別: 1on1/面談/連絡 */
    private String followupType;

    /** 実施日 */
    private LocalDate followupDate;

    /** 満足度 1-5 */
    private Integer satisfaction;

    /** トピック */
    private String topic;

    /** 内容 */
    private String content;

    /** 次回フォロー予定日 */
    private LocalDate nextDate;

    /** 登録者 */
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
