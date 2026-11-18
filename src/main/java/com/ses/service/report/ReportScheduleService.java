package com.ses.service.report;

import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;

import java.util.List;

/** 管理レポートschedule管理。enabled変更はcontrollerの管理者境界で保護する。 */
public interface ReportScheduleService {
    List<ReportSchedule> list();
    ReportSchedule create(ReportScheduleCreateRequest request);
    /**
     * scheduleを有効化/無効化する。retry上限で自動停止した行（{@code enabled = 0}、{@code lastErrorCode =
     * SCHEDULE_RETRY_EXHAUSTED}）の有効化は手動再開として扱い、連続失敗回数を0へ戻して保持中のretryから再開させる。
     */
    ReportSchedule setEnabled(Long scheduleId, boolean enabled);
}
