package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 隔離領域に保存したファイルのscan状態と公開状態。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_file_security_metadata")
public class FileSecurityMetadata extends BaseEntity {
    private String tenantId;
    private String storedName;
    private String fileKind;
    private String ownerType;
    private Long ownerId;
    private String storageState;
    private String scanStatus;
    private LocalDateTime scannedAt;
    private String scannerVersion;
    private String rejectionReason;
    private Long createdBy;
}
