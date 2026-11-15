package com.ses.service.ai;

import com.ses.entity.AuditLog;
import com.ses.mapper.AuditLogMapper;
import com.ses.service.AuditLogService;
import com.ses.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 本番profileの実設定で、AI外部送信監査の独立transaction（REQUIRES_NEW）が成立することを検証する。
 * <p>contextは application.yml・application-prod.yml をConfigDataで実際に読み込み（prod profile）、
 * Spring Boot実物の AopAutoConfiguration・TransactionAutoConfiguration・DataSourceTransactionManagerAutoConfiguration と
 * mainの {@link AuditLogServiceImpl} で組み立てる。test側ではproxy方式（@EnableTransactionManagement 等）を宣言しないため、
 * ymlの {@code spring.aop.proxy-target-class} や自動設定の既定が変わればCGLIB判定が失敗する。
 * 検知能力は {@code proxy-target-class=false} の反対例で確認する。</p>
 * <p>保証範囲は限定的である。DataSourceは専用H2、AuditLogMapperは書込み時のtransaction名と接続を記録する代替であり、
 * MySQL・MyBatis-Plusの挙動は対象外とする。mainの他の設定class（@EnableAsync 等）は読み込まないため、
 * それらによるproxy方式の変化もこの検査の対象外とする。</p>
 */
class ProdAuditProxyTransactionTest {

    private static ApplicationContextRunner prodRunner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=prod")
                .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class,
                        TransactionAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class))
                .withUserConfiguration(AuditProbeConfiguration.class, AuditLogServiceImpl.class);
    }

    @Test
    void 本番設定では監査serviceがCGLIB_proxyになる() {
        prodRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertRealProdConfigurationLoaded(context.getEnvironment());
            // 実ymlに指定が無ければSpring Bootの既定（true）でCGLIBになる
            assertThat(context.getEnvironment().getProperty("spring.aop.proxy-target-class", Boolean.class, Boolean.TRUE))
                    .isTrue();
            assertCglibAuditService(context);
        });
    }

    @Test
    void REQUIRES_NEWは別接続でcommitし外側rollback後も監査だけを残す() {
        // H2はcontextごとに一意名で作り、context終了時に破棄するため後始末は不要
        prodRunner().run(context -> {
            assertThat(context).hasNotFailed();
            AuditLogServiceImpl service = context.getBean(AuditLogServiceImpl.class);
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            PlatformTransactionManager manager = context.getBean(PlatformTransactionManager.class);
            String attempt = UUID.randomUUID().toString();
            String control = UUID.randomUUID().toString();
            long[] outerConnection = new long[1];
            requiresNew(manager).executeWithoutResult(outer -> {
                outerConnection[0] = jdbc.queryForObject("SELECT SESSION_ID()", Long.class);
                jdbc.update("INSERT INTO audit_probe(id_key) VALUES (?)", control);
                AuditLog entry = new AuditLog();
                entry.setActorType("SYSTEM");
                entry.setConfirmationSource("SCHEDULER_POLL");
                entry.setIdempotencyKey(attempt);
                service.recordDomainEventRequiredInNewTransaction(entry);
                outer.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_probe WHERE id_key = ?", Integer.class, attempt)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_probe WHERE id_key = ?", Integer.class, control)).isZero();
            assertThat(jdbc.queryForObject("SELECT connection_id FROM audit_probe WHERE id_key = ?", Long.class, attempt))
                    .isNotEqualTo(outerConnection[0]);
            assertThat(jdbc.queryForObject("SELECT tx_name FROM audit_probe WHERE id_key = ?", String.class, attempt))
                    .isEqualTo(AuditLogServiceImpl.class.getName() + ".recordDomainEventRequiredInNewTransaction");
        });
    }

    @Test
    void proxy_target_classをfalseにするとCGLIB判定が失敗する() {
        // 実ymlを読んだ上で設定だけを反転し、上のCGLIB判定が設定の変化を検知できることを示す
        prodRunner().withPropertyValues("spring.aop.proxy-target-class=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertRealProdConfigurationLoaded(context.getEnvironment());
            assertThat(AopUtils.isJdkDynamicProxy(context.getBean(AuditLogService.class))).isTrue();
            // AiExternalSendAuditorはAuditLogServiceImplとして受け取れず、REQUIRES_NEWの経路を通らない
            assertThat(context.getBeansOfType(AuditLogServiceImpl.class)).isEmpty();
            assertThatThrownBy(() -> assertCglibAuditService(context)).isInstanceOf(AssertionError.class);
        });
    }

    private static void assertCglibAuditService(ApplicationContext context) {
        AuditLogService bean = context.getBean(AuditLogService.class);
        assertThat(AopUtils.isCglibProxy(bean)).as("監査serviceはCGLIB proxyであること").isTrue();
        assertThat(bean).isInstanceOf(AuditLogServiceImpl.class);
        assertThat(AopUtils.getTargetClass(bean)).isEqualTo(AuditLogServiceImpl.class);
    }

    /** test側の既定値ではなく、mainの両ymlが実際に読み込まれたことを確かめる。 */
    private static void assertRealProdConfigurationLoaded(ConfigurableEnvironment environment) {
        assertThat(environment.getActiveProfiles()).contains("prod").doesNotContain("test", "dev");
        List<String> sources = environment.getPropertySources().stream().map(PropertySource::getName).toList();
        assertThat(sources).anyMatch(name -> name.contains("[application.yml]"));
        assertThat(sources).anyMatch(name -> name.contains("[application-prod.yml]"));
        assertThat(sources).noneMatch(name -> name.contains("[application-test.yml]"));
        // application.yml だけにある値と、application-prod.yml が上書きする値
        assertThat(environment.getProperty("mybatis-plus.global-config.db-config.logic-delete-field")).isEqualTo("deletedFlag");
        assertThat(environment.getProperty("app.security.require-https")).isEqualTo("true");
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager manager) {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    /** 専用H2と、書込み時のtransaction名・接続を記録するmapper代替だけを提供する。proxy方式はここで決めない。 */
    @Configuration(proxyBeanMethods = false)
    static class AuditProbeConfiguration {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
            new JdbcTemplate(db).execute("CREATE TABLE audit_probe(id_key VARCHAR(128) PRIMARY KEY, tx_name VARCHAR(255), connection_id BIGINT)");
            return db;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource db) { return new JdbcTemplate(db); }
        @Bean AuditLogMapper auditLogMapper(JdbcTemplate jdbc) {
            AuditLogMapper mapper = mock(AuditLogMapper.class);
            when(mapper.insert(any(AuditLog.class))).thenAnswer(call -> {
                AuditLog entry = call.getArgument(0);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return jdbc.update("INSERT INTO audit_probe(id_key, tx_name, connection_id) VALUES (?, ?, SESSION_ID())",
                        entry.getIdempotencyKey(), TransactionSynchronizationManager.getCurrentTransactionName());
            });
            return mapper;
        }
    }
}
