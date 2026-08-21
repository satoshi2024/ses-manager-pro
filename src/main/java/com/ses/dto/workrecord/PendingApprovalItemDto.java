package com.ses.dto.workrecord;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/** 承認待ち（提出済・未承認）1件分の滞留情報。読み取り専用の可視化用（トラックA3）。 */
@Data
public class PendingApprovalItemDto {
    private Long workRecordId;
    private Long contractId;
    private String contractNo;
    private String engineerName;
    /** 提出済のまま最後に更新されてからの経過日数。 */
    private int daysPending;
    /** MyBatis マッピング用。API レスポンスには出さない。 */
    @JsonIgnore
    private LocalDateTime updatedAt;
}
