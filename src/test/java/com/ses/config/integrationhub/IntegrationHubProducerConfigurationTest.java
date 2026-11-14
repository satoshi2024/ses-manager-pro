package com.ses.config.integrationhub;

import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import com.ses.service.integrationhub.IntegrationHubResourceChangedProducer;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationHubProducerConfigurationTest {
    @Test
    void 業務更新とenqueueは同じtransactionでcommitする() {
        verifyAtomic(false);
    }

    @Test
    void enqueue失敗は業務更新もrollbackする() {
        verifyAtomic(true);
    }

    @Test
    void 実案件作成serviceが生成IDをDTOへ返しadvisorがそのIDを通知する() {
        var customers = mock(com.ses.mapper.CustomerMapper.class);
        var customer = new com.ses.entity.Customer(); customer.setId(4L); customer.setLegalEntityId(9L);
        when(customers.selectByIdForTenant(4L, "tenant-a")).thenReturn(customer);
        var mapper = mock(com.ses.mapper.ProjectMapper.class);
        when(mapper.insert(any(com.ses.entity.Project.class))).thenAnswer(call -> {
            ((com.ses.entity.Project) call.getArgument(0)).setId(81L); return 1;
        });
        var real = new com.ses.service.impl.ProjectServiceImpl(mock(com.ses.mapper.ContractMapper.class),
                mock(com.ses.mapper.ProposalMapper.class), customers, provider(mock(com.ses.service.ProjectSkillService.class)));
        org.springframework.test.util.ReflectionTestUtils.setField(real, "baseMapper", mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(real, "legalEntityContextService",
                mock(com.ses.service.security.LegalEntityContextService.class));
        var source = new DriverManagerDataSource("jdbc:h2:mem:project_create_" + UUID.randomUUID(), "sa", "");
        var producer = mock(IntegrationHubResourceChangedProducer.class);
        ProxyFactory factory = new ProxyFactory(real);
        factory.addAdvisor(new IntegrationHubProducerConfiguration().integrationHubResourceProducerAdvisor(
                provider((PlatformTransactionManager) new DataSourceTransactionManager(source)), provider(producer)));
        var dto = new com.ses.dto.project.ProjectSaveDto(); dto.setCustomerId(4L);
        com.ses.service.accounting.AccountingTenantContextHolder.setTenantId("tenant-a");
        try {
            ((com.ses.service.ProjectService) factory.getProxy()).saveProjectWithSkills(dto);
            assertThat(dto.getId()).isEqualTo(81L);
            verify(producer).changed("project", 81L);
        } finally { com.ses.service.accounting.AccountingTenantContextHolder.clear(); }
    }

    private void verifyAtomic(boolean failEnqueue) {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:producer_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE business_row(id BIGINT PRIMARY KEY, state INT)");
        jdbc.execute("CREATE TABLE delivery_row(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO business_row VALUES (1, 0)");
        EngineerService business = mock(EngineerService.class);
        when(business.updateWithStatusGuard(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("UPDATE business_row SET state=1 WHERE id=1");
            return true;
        });
        IntegrationHubResourceChangedProducer producer = mock(IntegrationHubResourceChangedProducer.class);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("INSERT INTO delivery_row VALUES(1)");
            if (failEnqueue) throw new IllegalStateException("synthetic-enqueue-failure");
            return null;
        }).when(producer).changed("engineer-availability", 1L);
        var advisor = new IntegrationHubProducerConfiguration().integrationHubResourceProducerAdvisor(
                provider((PlatformTransactionManager) new DataSourceTransactionManager(source)), provider(producer));
        ProxyFactory factory = new ProxyFactory(business);
        factory.addAdvisor(advisor);
        EngineerService proxy = (EngineerService) factory.getProxy();
        Engineer engineer = new Engineer();
        engineer.setId(1L);
        try {
            if (failEnqueue) assertThatThrownBy(() -> proxy.updateWithStatusGuard(engineer)).isInstanceOf(IllegalStateException.class);
            else assertThat(proxy.updateWithStatusGuard(engineer)).isTrue();
            assertThat(jdbc.queryForObject("SELECT state FROM business_row WHERE id=1", Integer.class))
                    .isEqualTo(failEnqueue ? 0 : 1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_row", Integer.class))
                    .isEqualTo(failEnqueue ? 0 : 1);
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    private <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(bean);
        return provider;
    }
}
