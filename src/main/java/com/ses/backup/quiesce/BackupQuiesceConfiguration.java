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

    /**
     * gate を参照する側（HTTP の filter・監査・session・logout・scheduler・async・storage）が引く bean。実体は anchor の gate。
     * gate の {@code shutdown()} を破棄の callback として推定させない（SHUTDOWN に入る所は §6.11 の 2 つだけ）。
     */
    @Bean(destroyMethod = "")
    static BackupWriteGate backupWriteGate(BackupQuiesceLifecycleAnchor anchor) {
        return anchor.gate();
    }

    @Bean
    static BackupQuiesceDestructionOrderPostProcessor backupQuiesceDestructionOrderPostProcessor() {
        return new BackupQuiesceDestructionOrderPostProcessor();
    }

    /** 業務の {@code dataSource} を包む（BWQ-004）。BeanPostProcessor なので static にし、anchor は包む時に引く。 */
    @Bean
    static BackupQuiesceDataSourcePostProcessor backupQuiesceDataSourcePostProcessor(
            ObjectProvider<BackupQuiesceLifecycleAnchor> anchor) {
        return new BackupQuiesceDataSourcePostProcessor(anchor);
    }

    @Bean
    static BackupQuiesceMetrics backupQuiesceMetrics(BackupQuiesceLifecycleAnchor anchor) {
        return new BackupQuiesceMetrics(anchor.gate());
    }

    /**
     * prod の起動時検査（BWQ-007。§12.2）。静止応答は instance の登録の前にこの検査を呼ぶ。prod の profile で
     * {@code application-test.yml}（{@code enabled=false}）を import する全体の context の試験は {@code @MockBean} で置き換える。
     */
    @Bean
    static BackupQuiesceProductionValidator backupQuiesceProductionValidator(Environment environment,
                                                                             BackupQuiesceProperties properties) {
        return new BackupQuiesceProductionValidator(environment, properties);
    }

    @Bean
    static BackupQuiesceResponder backupQuiesceResponder(BackupQuiesceLifecycleAnchor anchor, BackupQuiesceMetrics metrics,
                                                         @Qualifier("taskExecutor") ObjectProvider<Executor> taskExecutor,
                                                         ObjectProvider<BackupQuiesceProductionValidator> validator) {
        return new BackupQuiesceResponder(anchor, metrics, taskExecutor, validator);
    }
}
