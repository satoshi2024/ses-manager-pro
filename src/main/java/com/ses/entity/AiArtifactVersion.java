package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_ai_artifact_version")
public class AiArtifactVersion extends BaseEntity {

    private String useCase;
    private String provider;
    private String modelName;
    private String promptVersion;
    private String ruleVersion;
    private String configHash;
    private String status;
    private Integer statusVersion;
    private LocalDateTime activatedAt;
    private LocalDateTime retiredAt;
}
