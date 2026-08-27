package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * サービスリクエスト状態変更監査イベントエンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_service_state_event")
public class ServiceStateEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long serviceRequestId;

    private Integer roundNo;

    private String fromStatus;

    private String toStatus;

    private String reason;

    private String actorType;

    private Long actorId;

    private String actorName;

    private LocalDateTime createdAt;
}
