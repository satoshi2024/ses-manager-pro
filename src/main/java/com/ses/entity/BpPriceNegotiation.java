package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BP価格協議記録エンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_bp_price_negotiation")
public class BpPriceNegotiation extends BaseEntity {

    private Long tenantId;
    private Long bpCompanyId;
    private LocalDate requestedAt;
    private LocalDate respondedAt;
    private String status; // REQUESTED / RESPONDED / AGREED / REJECTED
    private BigDecimal requestedAmount;
    private BigDecimal agreedAmount;
    private String summary;
    private Long documentId;
}
