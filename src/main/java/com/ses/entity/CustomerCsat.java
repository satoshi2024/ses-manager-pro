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
 * 顧客満足度調査回答 (CSAT) エンティティ
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_csat")
public class CustomerCsat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long serviceRequestId;

    private Long customerId;

    private Long portalUserId;

    private Integer score;

    private String feedbackComment;

    private LocalDateTime answeredAt;
}
