package com.ses.service.scheduler;

import com.ses.service.ContractRenewalService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 毎日7:30に、auto_renew=1の契約について自動更新ドラフトを生成する
 * （通知生成バッチ8:00より前に実行し、ドラフト作成後の状態で当日の通知が出るようにする）。
 */
@Component
@RequiredArgsConstructor
public class ContractRenewalScheduler {

    private final ContractRenewalService contractRenewalService;

    @Scheduled(cron = "0 30 7 * * *")
    public void generateDaily() {
        contractRenewalService.generateRenewalDrafts();
    }
}
