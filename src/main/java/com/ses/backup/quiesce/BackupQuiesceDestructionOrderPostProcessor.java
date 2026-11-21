package com.ses.backup.quiesce;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 停止の時の破棄の順を固定する（design.md §6.11。D3-P2-1・D3-P3-1）。
 *
 * <ul>
 *   <li>{@code taskExecutor} と Spring Boot の {@code taskScheduler} の依存に {@code dataSource} を足す: executor・scheduler は
 *       業務の DataSource より先に破棄される（executor は最長 30 秒 task の終わりを待ち、scheduler は先に止まる）。</li>
 *   <li>{@code dataSource} の依存に {@link BackupQuiesceLifecycleAnchor} <b>だけ</b>を足す: anchor と制御用・heartbeat 用の pool は
 *       業務の DataSource より後に破棄され、業務の pool を閉じた後でも STOPPED を書ける。</li>
 * </ul>
 * 静止応答（{@code BackupQuiesceResponder}）は {@code dataSource} の依存に入れない（{@code taskExecutor → dataSource → 静止応答 →
 * taskExecutor} の循環を作らないため）。対象の bean が無い時（scheduling が無効で {@code taskScheduler} が無い prod の初回起動、
 * DataSource の無い試験の slice 等）は何もしない（D4-P3-2）。
 */
public class BackupQuiesceDestructionOrderPostProcessor implements BeanFactoryPostProcessor {

    static final String DATA_SOURCE = "dataSource";
    static final List<String> EXECUTORS = List.of("taskExecutor", "taskScheduler");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (!beanFactory.containsBeanDefinition(DATA_SOURCE)) {
            return;
        }
        if (beanFactory.containsBeanDefinition(BackupQuiesceLifecycleAnchor.BEAN_NAME)) {
            addDependsOn(beanFactory.getBeanDefinition(DATA_SOURCE), BackupQuiesceLifecycleAnchor.BEAN_NAME);
        }
        for (String executor : EXECUTORS) {
            if (beanFactory.containsBeanDefinition(executor)) {
                addDependsOn(beanFactory.getBeanDefinition(executor), DATA_SOURCE);
            }
        }
    }

    private static void addDependsOn(BeanDefinition definition, String dependency) {
        Set<String> dependsOn = new LinkedHashSet<>();
        if (definition.getDependsOn() != null) {
            dependsOn.addAll(Arrays.asList(definition.getDependsOn()));
        }
        dependsOn.add(dependency);
        definition.setDependsOn(dependsOn.toArray(String[]::new));
    }
}
