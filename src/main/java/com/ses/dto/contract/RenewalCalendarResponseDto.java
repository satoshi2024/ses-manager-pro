package com.ses.dto.contract;

import lombok.Data;

import java.util.List;

/**
 * GET /api/contracts/renewal-calendar のレスポンス全体。
 * truncated=true は1000件上限で切り詰められたことを示す（A7-22: 黙って欠けない）。
 */
@Data
public class RenewalCalendarResponseDto {
    private List<RenewalCalendarItemDto> items;
    private boolean truncated;
    private int leadDays;
}
