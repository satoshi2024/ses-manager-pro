package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AIログエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_log")
public class AiLog extends BaseEntity {
    private Long id;
    
    /** リクエストタイプ: 'マッチング','スキルシート','営業メール' */
    private String requestType;
    
    /** リクエストパラメータ (JSON) */
    private String requestParams;
    
    /** レスポンステキスト */
    private String responseText;
    
    /** 使用トークン数 */
    private Integer tokensUsed;
    
    /** コスト (円) */
    private BigDecimal costJpy;
    
    /** 作成者ID */
    private Long createdBy;
    
    /** 作成日時 */
    private LocalDateTime createdAt;
}
