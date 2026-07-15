package com.ses.service;

import com.ses.dto.analytics.BenchEngineerDto;
import com.ses.dto.analytics.UtilizationPointDto;

import java.util.List;

/**
 * 稼動分析サービス
 */
public interface AnalyticsService {
    /**
     * 直近N月の月次稼動率推移を返す
     * @param months 対象月数
     */
    List<UtilizationPointDto> utilizationTrend(int months);

    /**
     * 現在Bench中の要員一覧をBench経過日数降順で返す
     */
    List<BenchEngineerDto> benchList();

    /**
     * エンジニア稼働タイムラインデータを返す
     * @param fromMonth 取得開始月 (YYYY-MM)
     * @param toMonth 取得終了月 (YYYY-MM)
     * @param skillId スキルフィルタ
     * @param salesUserId 担当営業フィルタ
     */
    com.ses.dto.analytics.AvailabilityTimelineDto getAvailabilityTimeline(String fromMonth, String toMonth, Long skillId, Long salesUserId);
}
