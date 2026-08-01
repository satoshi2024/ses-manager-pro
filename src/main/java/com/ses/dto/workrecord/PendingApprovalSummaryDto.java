package com.ses.dto.workrecord;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 対象月の承認滞留サマリ（読み取り専用）。件数と最長滞留日数、滞留日数の降順で並べた明細を返す。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingApprovalSummaryDto {
    /** 提出済（未承認）の総件数。ページングの有無に関わらず全件を数える。 */
    private int submittedCount;
    /** 提出済の中での最長滞留日数（0件のときは null）。 */
    private Integer maxPendingDays;
    /** 滞留日数の降順（長い順）に並べた明細。安全上限は PageUtils.safePage で丸める。 */
    private List<PendingApprovalItemDto> items;
}
