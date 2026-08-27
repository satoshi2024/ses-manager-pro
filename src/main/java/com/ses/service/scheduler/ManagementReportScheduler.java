package com.ses.service.scheduler;

import com.ses.entity.ReportSchedule;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.service.MonthlyClosingService;
import com.ses.service.report.ReportDeliveryService;
import com.ses.service.report.ReportSnapshotService;
import com.ses.dto.report.ReportGenerationCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.List;

/**
 * scheduleはShedLockとDBのnext_run_at CASを併用し、HTTP sessionを使わずsystem principalで実行する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementReportScheduler {

    private static final String TIMEZONE = "Asia/Tokyo";
    private final ReportScheduleMapper scheduleMapper;
    private final ReportSnapshotService snapshotService;
    private final ReportDeliveryService deliveryService;
    private final MonthlyClosingService monthlyClosingService;

    @Scheduled(cron = "${management-report.schedule-cron:0 * * * * *}", zone = TIMEZONE)
    @SchedulerLock(name = "managementReportScheduleDispatch", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void dispatchDue() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of(TIMEZONE));
        List<ReportSchedule> due = scheduleMapper.selectDue(now, 50);
        for (ReportSchedule schedule : due) {
            LocalDateTime expected = schedule.getNextRunAt();
            if (expected == null) continue;
            LocalDateTime next = expected.plusMonths(1);
            if (scheduleMapper.claimDue(schedule.getId(), expected, next, now) != 1) continue;
            runOne(schedule, expected);
        }
    }

    public void runOne(ReportSchedule schedule, LocalDateTime scheduledAt) {
        YearMonth target = YearMonth.from(scheduledAt).minusMonths(1);
        String cutoff = monthlyClosingService.isClosed(target.toString()) ? "確定" : "速報";
        try {
            var result = snapshotService.generate(ReportGenerationCommand.scheduled(
                    schedule.getTemplateVersionId(), target, cutoff, schedule.getId(), schedule.getCreatedBy()));
            if (result.getRun() != null && "SUCCEEDED".equals(result.getRun().getStatus())) {
                deliveryService.deliver(result.getRun().getId(), null);
            }
        } catch (Exception ex) {
            // 1件のschedule失敗で他scheduleを止めず、run/delivery側の安全な状態を保持する。
            log.error("[定期管理レポート] schedule実行失敗: scheduleId={}", schedule.getId(), ex);
        }
    }
}
