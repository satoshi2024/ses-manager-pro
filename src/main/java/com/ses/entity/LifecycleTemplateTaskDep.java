package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.io.Serializable;

/**
 * テンプレートタスク依存関係エンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("m_lifecycle_template_task_dep")
public class LifecycleTemplateTaskDep implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long templateId;

    private String predecessorTaskCode;

    private String successorTaskCode;
}
