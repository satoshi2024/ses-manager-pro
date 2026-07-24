package com.ses.dto.ai;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 提案下書きDTO
 */
@Data
public class ProposalDraftDto {
    /** 提案メール本文 */
    private String emailText;
    /** マッチング理由 */
    private String matchReason;
    /** アピールポイント */
    private String sellingPoints;
    /** AIマッチスコア (0-100) */
    private BigDecimal matchScore;
}
