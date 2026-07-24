package com.ses.service.scheduler;

import com.ses.service.RenewalEscalationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 毎日8:15に、未対応の契約更新についてエスカレーション通知を発行する（FR-06）。
 * 更新ドラフト生成バッチ(7:30)・通知生成バッチ(8:00)より後に実行し、
 * 当日生成されたドラフト/通知を踏まえた状態で判定する。
 */
@Component
@RequiredArgsConstructor
public class RenewalEscalationScheduler {

    private final RenewalEscalationService renewalEscalationService;

    @Scheduled(cron = "0 15 8 * * *")
    public void escalateDaily() {
        renewalEscalationService.escalateUnhandled();
    }
}
