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
 * 顧客満足度調査回答エンティティ（t_customer_csat）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_customer_csat")
public class CustomerCsat {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 対象サービスリクエストID */
    private Long serviceRequestId;

    /** 顧客ID */
    private Long customerId;

    /** 回答ポータルユーザーID */
    private Long portalUserId;

    /** 評価スコア (1-5) */
    private Integer score;

    /** フィードバックコメント */
    private String feedbackComment;

    /** 回答日時 */
    private LocalDateTime answeredAt;
}
