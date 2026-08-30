package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.time.LocalDate;

/**
 * ライセンス割当台帳エンティティ (t_license_assignment)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_license_assignment")
public class LicenseAssignment extends BaseEntity {

    /**
     * ライセンスプランID (m_license_plan.id)
     */
    private Long planId;

    /**
     * 貸与先区分: ENGINEER, USER
     */
    private String assigneeType;

    /**
     * 要員IDまたはユーザーID
     */
    private Long assigneeId;

    /**
     * 関連外部アカウント参照ID (t_external_account_reference.id)
     */
    private Long accountReferenceId;

    /**
     * 割当開始日
     */
    private LocalDate assignedDate;

    /**
     * 割当解除日 (NULL=現在割当中)
     */
    private LocalDate releasedDate;

    /**
     * ステータス: ACTIVE, RELEASED
     */
    @Builder.Default
    private String status = "ACTIVE";

    /**
     * 楽観ロック用バージョン
     */
    @Version
    @Builder.Default
    private Integer version = 0;
}
