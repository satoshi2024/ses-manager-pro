package com.ses.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_peppol_participant")
public class PeppolParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 参加者IDの権威テナント。 */
    private String tenantId;

    /** 参加者IDの権威法人。 */
    private Long legalEntityId;

    private String ownerType;
    private Long ownerId;
    private String schemeId;
    private String participantId;
    private String provider;
    private String status;
    private LocalDateTime verifiedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableLogic
    private Integer deletedFlag;
}
