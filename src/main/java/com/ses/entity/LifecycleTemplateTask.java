package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

/**
 * テンプレートタスク定義エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_lifecycle_template_task")
public class LifecycleTemplateTask extends BaseEntity {

    /**
     * 所属テンプレートID (m_lifecycle_template.id)
     */
    private Long templateId;

    /**
     * タスクコード
     */
    private String taskCode;

    /**
     * タスク名
     */
    private String taskName;

    /**
     * 説明・手順
     */
    private String description;

    /**
     * 基準日からの相対日数 (-7, 0, 3等)
     */
    @Builder.Default
    private Integer relativeDueDays = 0;

    /**
     * 担当解決ルール (SPECIFIC_USER, ROLE, ORGANIZATION_MANAGER, PRIMARY_SALES, ENGINEER_SELF, APPLICANT)
     */
    private String assigneeRule;

    /**
     * 担当解決ルールの補助値 (Role名や特定UserID等)
     */
    private String assigneeRuleValue;

    /**
     * 必須区分 (1: 必須, 0: 任意)
     */
    @Builder.Default
    private Integer isMandatory = 1;

    /**
     * 完了阻害区分 (1: 案件完了を阻害, 0: 非阻害)
     */
    @Builder.Default
    private Integer isBlocking = 1;

    /**
     * 証跡種別 (NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK)
     */
    @Builder.Default
    private String evidenceType = "NONE";

    /**
     * 本人公開区分 (1: 本人公開, 0: 内部限定)
     */
    @Builder.Default
    private Integer isEngineerVisible = 1;

    /**
     * 対象雇用形態カンマ区切り (正社員,契約社員,BP / null=全形態)
     */
    private String targetEmploymentTypes;

    /**
     * 表示順
     */
    @Builder.Default
    private Integer sortOrder = 0;
}
