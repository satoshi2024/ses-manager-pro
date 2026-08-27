package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエスト（問い合わせ・課題）エンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_request")
public class ServiceRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String requestNo;

    private Long customerId;

    private Long contactId;

    private Long contractId;

    private Long projectId;

    private Long engineerId;

    private String category;

    private String priority;

    private String channel;

    private String subject;

    private String description;

    private Long ownerUserId;

    private String status;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolvedAt;

    private LocalDateTime closedAt;

    private LocalDateTime reopenedAt;

    private Integer reopenCount;

    private Long portalUserId;

    private Long createdBy;

    private Long updatedBy;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
