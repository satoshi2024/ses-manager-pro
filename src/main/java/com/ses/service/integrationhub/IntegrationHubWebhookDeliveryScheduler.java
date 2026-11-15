package com.ses.service.integrationhub;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 外部transport有効時だけ生成するB1 scheduler。testではapp.scheduling=falseで生成しない。 */
@Component
@ConditionalOnIntegrationHubDeliveryScheduling
public class IntegrationHubWebhookDeliveryScheduler {
    private final IntegrationHubWebhookDeliveryWorker worker;

    public IntegrationHubWebhookDeliveryScheduler(IntegrationHubWebhookDeliveryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${integration.hub.external-transport.fixed-delay-ms:1000}")
    @net.javacrumbs.shedlock.spring.annotation.SchedulerLock(
            name = "integrationHubWebhookDelivery", lockAtMostFor = "PT5M")
    public void dispatch() {
        worker.dispatchDue();
    }
}
