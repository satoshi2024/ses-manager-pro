package com.ses.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定期バッチの有効化設定。
 *
 * <p>通常実行では既定で有効にし、テストでは明示的に無効化する。これにより、長時間の
 * テストスイート中に分・秒単位のschedulerが共有テストDBを更新することを防ぐ。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
