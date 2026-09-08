package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;

import java.time.LocalDateTime;

/** REQ-YYYYMM-XXXX のtenant/月次採番行。更新はmapperのCAS相当SQLだけで行う。 */
@Data
@TableName("t_service_request_sequence")
public class ServiceRequestSequence {

    @TableId(value = "tenant_id", type = IdType.INPUT)
    private String tenantId;
    private String requestMonth;
    private Integer lastNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
