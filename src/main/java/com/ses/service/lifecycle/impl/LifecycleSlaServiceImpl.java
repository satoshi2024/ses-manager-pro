package com.ses.service.lifecycle.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.service.lifecycle.LifecycleNotificationService;
import com.ses.service.lifecycle.LifecycleSlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ライフサイクル SLA 監視サービス実装
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleSlaServiceImpl implements LifecycleSlaService {

    private final LifecycleCaseMapper caseMapper;
    private final LifecycleTaskMapper taskMapper;
    private final LifecycleNotificationService notificationService;

    @Override
    @Transactional
    public int processSlaCheck(LocalDate asOf) {
        LocalDate today = asOf != null ? asOf : LocalDate.now();

        // 進行中の全案件を取得
        List<LifecycleCase> activeCases = caseMapper.selectList(
                new LambdaQueryWrapper<LifecycleCase>()
                        .eq(LifecycleCase::getStatus, "ACTIVE")
        );

        int processedCount = 0;

        for (LifecycleCase lcCase : activeCases) {
            List<LifecycleTask> tasks = taskMapper.selectByCaseId(lcCase.getId());

            for (LifecycleTask task : tasks) {
                // 完了済み・免除済みタスクは対象外
                if ("COMPLETED".equals(task.getStatus()) || "WAIVED".equals(task.getStatus())) {
                    continue;
                }

                if (task.getDueDate() == null) {
                    continue;
                }

                long daysUntilDue = ChronoUnit.DAYS.between(today, task.getDueDate());

                if (daysUntilDue < 0) {
                    // 期日超過 (Overdue)
                    long daysOverdue = Math.abs(daysUntilDue);
                    notificationService.notifyTaskOverdue(task, lcCase, daysOverdue);
                    processedCount++;
                } else if (daysUntilDue <= 3) {
                    // 期日接近 (3日以内: 3日前・前日・当日)
                    notificationService.notifyTaskDueSoon(task, lcCase, daysUntilDue);
                    processedCount++;
                }
            }
        }

        log.info("Lifecycle SLA check completed for date {}: {} tasks processed for notifications.", today, processedCount);
        return processedCount;
    }
}
