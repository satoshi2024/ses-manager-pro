package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * ライフサイクルテンプレートエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_lifecycle_template")
public class LifecycleTemplate extends BaseEntity {

    /**
     * テンプレート種別 (JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION)
     */
    private String templateType;

    /**
     * テンプレート名
     */
    private String name;

    /**
     * 説明
     */
    private String description;

    /**
     * 版番号 (改定ごとに+1)
     */
    @Builder.Default
    private Integer versionNo = 1;

    /**
     * ステータス (ACTIVE, INACTIVE)
     */
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 有効開始日 (inclusive)
     */
    private LocalDate validFrom;

    /**
     * 有効終了日 (inclusive, null=無期限)
     */
    private LocalDate validTo;

    /**
     * 作成者ユーザーID
     */
    private Long createdBy;

    /**
     * 更新者ユーザーID
     */
    private Long updatedBy;
}
