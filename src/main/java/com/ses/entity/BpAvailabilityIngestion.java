package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 要員空き状況メール取込ジョブエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_bp_availability_ingestion")
public class BpAvailabilityIngestion extends BaseEntity {

    private String originalFileName;
    private String storedFileName;
    private String fileExt;
    private String status;
    private String extractedText;
    private String parsedJson;
    private String aiProvider;
    private String aiModel;
    private String errorMessage;
    private Long convertedAvailabilityId;
    private String reviewNote;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
