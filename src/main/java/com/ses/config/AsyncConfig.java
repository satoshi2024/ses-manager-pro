package com.ses.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

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
            @Value("${app.async.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("async-notify-");
        // 取込の解析(@Async)はAI応答待ちで数秒かかる。既定の AbortPolicy のままだと
        // キュー満杯時に RejectedExecutionException となり、ジョブが「取込待ち」のまま
        // 永久に放置される。呼び出し元スレッドで実行して取りこぼさない。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
