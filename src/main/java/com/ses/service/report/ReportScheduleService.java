package com.ses.service.report;

import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.entity.ReportSchedule;

import java.util.List;

/** 管理レポートschedule管理。enabled変更はcontrollerの管理者境界で保護する。 */
public interface ReportScheduleService {
    List<ReportSchedule> list();
    ReportSchedule create(ReportScheduleCreateRequest request);
    ReportSchedule setEnabled(Long scheduleId, boolean enabled);
}
