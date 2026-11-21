package com.ses.backup.quiesce;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.concurrent.Executor;

/**
 * backup の書込み静止の部品の登録（design.md §5・§6・§12）。
 *
 * <p>bean の依存の向き（D3-P2-1）: {@code taskExecutor}・{@code taskScheduler} → {@code dataSource} →
 * {@link BackupQuiesceLifecycleAnchor} の一方向だけにする（{@link BackupQuiesceDestructionOrderPostProcessor}）。anchor は
 * {@link DataSourceProperties} と {@link BackupQuiesceProperties} にだけ依存する static な bean で、この設定 class の instance にも
 * 依存しない。静止応答（{@link BackupQuiesceResponder}）は {@code taskExecutor} を {@code ObjectProvider} で使う時に引く。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackupQuiesceProperties.class)
public class BackupQuiesceConfiguration {

    @Bean(name = BackupQuiesceLifecycleAnchor.BEAN_NAME)
    static BackupQuiesceLifecycleAnchor backupQuiesceLifecycleAnchor(DataSourceProperties dataSourceProperties,
                                                                    BackupQuiesceProperties properties,
                                                                    Environment environment) {
        return new BackupQuiesceLifecycleAnchor(dataSourceProperties, properties, environment);
    }

    @Bean
    static BackupQuiesceDestructionOrderPostProcessor backupQuiesceDestructionOrderPostProcessor() {
        return new BackupQuiesceDestructionOrderPostProcessor();
    }

    @Bean
    static BackupQuiesceMetrics backupQuiesceMetrics(BackupQuiesceLifecycleAnchor anchor) {
        return new BackupQuiesceMetrics(anchor.gate());
    }

    @Bean
    static BackupQuiesceResponder backupQuiesceResponder(BackupQuiesceLifecycleAnchor anchor, BackupQuiesceMetrics metrics,
                                                         @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutor) {
        return new BackupQuiesceResponder(anchor, metrics, taskExecutor);
    }
}
