package com.ses.config;

import com.ses.backup.quiesce.BackupQuiesceMetrics;
import com.ses.backup.quiesce.BackupQuiesceTaskDecorator;
import com.ses.backup.quiesce.BackupWriteGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 非同期処理用スレッドプール設定。
 * Webhook通知転送等の@Asyncメソッドが、通知バッチの本処理をブロックしないよう
 * 専用スレッドプールで実行されるようにする。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor(
            @Value("${app.async.core-pool-size:4}") int corePoolSize,
            @Value("${app.async.max-pool-size:16}") int maxPoolSize,
            @Value("${app.async.queue-capacity:200}") int queueCapacity,
            ObjectProvider<BackupWriteGate> backupWriteGate,
            ObjectProvider<BackupQuiesceMetrics> backupQuiesceMetrics) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-notify-");
        // 取込の解析(@Async)はAI応答待ちで数秒かかる。既定の AbortPolicy のままだと
        // キュー満杯時に RejectedExecutionException となり、ジョブが「取込待ち」のまま
        // 永久に放置される。呼び出し元スレッドで実行して取りこぼさない。
        // backup の書込み静止（RB-QUIESCE BWQ-006）: CallerRunsPolicy と同じに呼出し元で実行し、executor の停止の後に捨てる task は
        // 投入時に数えた permit を返す。
        executor.setRejectedExecutionHandler(BackupQuiesceTaskDecorator.rejectedExecutionHandler());
        // 投入時に async の permit を数え（静止の排水の対象）、閉じた後の投入は待たずに保留する（design.md §6.7）
        executor.setTaskDecorator(new BackupQuiesceTaskDecorator(backupWriteGate::getIfAvailable,
                backupQuiesceMetrics::getIfAvailable));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
