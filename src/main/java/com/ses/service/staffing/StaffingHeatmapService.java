package com.ses.service.staffing;

import com.ses.dto.staffing.HeatmapDto;
import com.ses.dto.staffing.ShortfallDrilldownDto;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 需給heatmap集計（T078 B1）。
 *
 * <p>月別×skill/role/location別の需要・供給・不足・余剰・bench costをserver aggregateで返す。
 * <ul>
 *   <li>需要FTE: position（required_count × allocation_percent）を月内稼働日数比で按分。</li>
 *   <li>供給FTE: {@link StaffingCapacityService#supply} と同一口径（actual+plan）。</li>
 *   <li>不足=max(0, 需要-供給)、余剰=max(0, 供給-需要)。全社合計と内訳合計が一致する。</li>
 *   <li>bench cost: （1.0 - 供給FTE）× 希望単価。HRロールではmask。</li>
 *   <li>planning horizonは最大24か月。超える要求は拒否（design §4/§5.4）。</li>
 *   <li>呼出側がscope適用済みのengineer集合を渡す（SQLはengineer_id INで境界化）。</li>
 * </ul>
 */
public interface StaffingHeatmapService {

    /** 当月から24か月分のheatmap。 */
    HeatmapDto heatmap(LocalDate asOf);

    /** 指定期間（最大24か月・計画window内）のheatmap。超える要求はBusinessException。 */
    HeatmapDto heatmap(LocalDate asOf, YearMonth from, YearMonth to);

    /** 不足セルのdrilldown（需要側position＋供給側engineer）。 */
    ShortfallDrilldownDto drilldown(YearMonth month, String dimension, String group, LocalDate asOf);
}
