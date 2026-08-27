package com.ses.dto.lifecycle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * テンプレートタスク定義DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LifecycleTemplateTaskDto {

    private Long id;
    private Long templateId;
    private String taskCode;
    private String taskName;
    private String description;
    private Integer relativeDueDays;
    private String assigneeRule;
    private String assigneeRuleValue;
    private Integer isMandatory;
    private Integer isBlocking;
    private String evidenceType;
    private Integer isEngineerVisible;
    private String targetEmploymentTypes;
    private Integer sortOrder;
    private List<String> predecessorTaskCodes;
}
