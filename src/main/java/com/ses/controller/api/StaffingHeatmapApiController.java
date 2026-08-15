package com.ses.controller.api;

import com.ses.common.result.ApiResult;
import com.ses.dto.staffing.HeatmapDto;
import com.ses.dto.staffing.ShortfallDrilldownDto;
import com.ses.service.staffing.StaffingHeatmapService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 需給heatmap API（T078 B1）。
 * analyticsメニューのapi_prefix（/api/analytics）配下に置き、既存のmenu権限をそのまま使う。
 * scope（DataScope/組織scope）はservice側でengineer/position集合に適用される。
 */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class StaffingHeatmapApiController {

    private final StaffingHeatmapService heatmapService;

    /** 需給heatmap（当月〜24か月）。from/to指定で範囲を絞れる（最大24か月・window内）。 */
    @GetMapping("/staffing-heatmap")
    public ApiResult<HeatmapDto> heatmap(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        LocalDate asOf = LocalDate.now();
        if (from == null && to == null) {
            return ApiResult.success(heatmapService.heatmap(asOf));
        }
        YearMonth fromMonth = from == null ? YearMonth.from(asOf) : YearMonth.parse(from);
        YearMonth toMonth = to == null ? fromMonth.plusMonths(11) : YearMonth.parse(to);
        return ApiResult.success(heatmapService.heatmap(asOf, fromMonth, toMonth));
    }

    /** 不足セルのdrilldown（需要側position＋供給側engineer）。 */
    @GetMapping("/staffing-heatmap/drilldown")
    public ApiResult<ShortfallDrilldownDto> drilldown(
            @RequestParam String month,
            @RequestParam String dimension,
            @RequestParam String group) {
        return ApiResult.success(heatmapService.drilldown(
                YearMonth.parse(month), dimension, group, LocalDate.now()));
    }
}
