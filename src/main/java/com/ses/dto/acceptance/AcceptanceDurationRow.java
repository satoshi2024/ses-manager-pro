package com.ses.dto.acceptance;

import lombok.Data;

import java.time.LocalDateTime;

/** 検収期間の集計行（提出→検収の所要日数算出用）。 */
@Data
public class AcceptanceDurationRow {
    private LocalDateTime submittedAt;
    private LocalDateTime acceptedAt;
}
