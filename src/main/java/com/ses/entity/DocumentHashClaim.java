package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文書HashアトミックClaimテーブル (t_document_hash_claim).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_document_hash_claim")
public class DocumentHashClaim {

    private String tenantId;

    private String documentType;

    private String sha256;

    private Long documentId;

    private LocalDateTime createdAt;
}
