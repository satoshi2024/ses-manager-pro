package com.ses.service.ai;

import com.ses.entity.AuditLog;
import com.ses.mapper.AuditLogMapper;
import com.ses.service.impl.AuditLogServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 本番と同じCGLIB transaction設定を、専用H2で実際のcommit/rollbackまで検証する。 */
@SpringJUnitConfig(ProdAuditProxyTransactionTest.ProductionTransactionConfiguration.class)
@ActiveProfiles("prod")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProdAuditProxyTransactionTest {
    @Autowired private AuditLogServiceImpl service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private final List<String> ownKeys = new ArrayList<>();

    @AfterEach
    void removeOwnCommittedRows() {
        requiresNew().executeWithoutResult(tx -> {
            for (String key : ownKeys) jdbc.update("DELETE FROM audit_probe WHERE id_key = ?", key);
        });
    }

    @Test
    void prod相当の監査serviceはCGLIB_proxyである() {
        assertThat(AopUtils.isCglibProxy(service)).isTrue();
        assertThat(AopUtils.getTargetClass(service)).isEqualTo(AuditLogServiceImpl.class);
    }

    @Test
    void REQUIRES_NEWは別接続でcommitし外側rollback後も監査だけを残す() {
        String attempt = key();
        String control = key();
        long[] outerConnection = new long[1];
        requiresNew().executeWithoutResult(outer -> {
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
    }

    private String key() { String value = UUID.randomUUID().toString(); ownKeys.add(value); return value; }
    private TransactionTemplate requiresNew() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return tx;
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("prod")
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ProductionTransactionConfiguration {
        @Bean(destroyMethod = "shutdown")
        EmbeddedDatabase dataSource() {
            EmbeddedDatabase db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
            new JdbcTemplate(db).execute("CREATE TABLE audit_probe(id_key VARCHAR(128) PRIMARY KEY, tx_name VARCHAR(255), connection_id BIGINT)");
            return db;
        }
        @Bean JdbcTemplate jdbcTemplate(EmbeddedDatabase db) { return new JdbcTemplate(db); }
        @Bean PlatformTransactionManager transactionManager(EmbeddedDatabase db) { return new DataSourceTransactionManager(db); }
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
        @Bean AuditLogServiceImpl auditLogService(AuditLogMapper mapper) { return new AuditLogServiceImpl(mapper); }
    }
}
