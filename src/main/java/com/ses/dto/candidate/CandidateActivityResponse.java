package com.ses.dto.candidate;

import com.ses.entity.CandidateActivity;
import lombok.Data;

import java.time.LocalDateTime;

/** 候補者活動履歴の安全な表示DTO。 */
@Data
public class CandidateActivityResponse {
    private Long id;
    private String stage;
    private String reason;
    private LocalDateTime changedAt;
    private String remarks;

    public static CandidateActivityResponse from(CandidateActivity source) {
        CandidateActivityResponse target = new CandidateActivityResponse();
        target.id = source.getId();
        target.stage = source.getStage();
        target.reason = source.getReason();
        target.changedAt = source.getChangedAt();
        target.remarks = source.getRemarks();
        return target;
    }
}
