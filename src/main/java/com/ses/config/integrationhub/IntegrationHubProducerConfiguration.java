package com.ses.config.integrationhub;

import com.ses.service.integrationhub.IntegrationHubResourceChangedProducer;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 基本CRUDの業務更新とresource.changedを同一transactionへ束縛する。OFF時はadvisorを登録しない。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.context.annotation.Conditional(com.ses.service.integrationhub.IntegrationHubOutboundCondition.class)
public class IntegrationHubProducerConfiguration {
    @Bean
    @org.springframework.context.annotation.Role(org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE)
    Advisor integrationHubResourceProducerAdvisor(ObjectProvider<PlatformTransactionManager> managers,
            ObjectProvider<IntegrationHubResourceChangedProducer> producers) {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(java.lang.reflect.Method method, Class<?> target) {
                if (method.getParameterCount() == 0) return false;
                String name = method.getName();
                if (com.ses.service.EngineerService.class.isAssignableFrom(target))
                    return java.util.Set.of("save", "updateById", "updateWithStatusGuard").contains(name);
                if (com.ses.service.ProjectService.class.isAssignableFrom(target))
                    return java.util.Set.of("save", "updateById", "saveProjectWithSkills", "updateProjectWithSkills").contains(name);
                if (com.ses.service.ContractService.class.isAssignableFrom(target))
                    return java.util.Set.of("save", "updateById", "saveWithBusinessRules", "updateWithBusinessRules",
                            "changeStatus", "createDraftFromProposal", "createDraftFromQuotation",
                            "createDraftFromSalesOrderLine", "updateRenewalDecision").contains(name);
                if (com.ses.service.InvoiceService.class.isAssignableFrom(target))
                    return java.util.Set.of("save", "updateById", "generate", "changeStatus", "addPayment",
                            "deletePayment", "voidInvoice").contains(name);
                return false;
            }
        };
        return new DefaultPointcutAdvisor(pointcut, (org.aopalliance.intercept.MethodInterceptor) invocation ->
                new TransactionTemplate(managers.getObject()).execute(transaction -> {
                    try {
                        Object result = invocation.proceed();
                        if (!Boolean.FALSE.equals(result)) {
                            Object source = invocation.getArguments()[0];
                            Long id = source instanceof com.ses.entity.Engineer row ? row.getId()
                                    : source instanceof com.ses.entity.Project row ? row.getId()
                                    : source instanceof com.ses.entity.Contract row ? row.getId()
                                    : source instanceof com.ses.entity.Invoice row ? row.getId()
                                    : source instanceof com.ses.dto.project.ProjectSaveDto row ? row.getId()
                                    : result instanceof com.ses.entity.Contract row ? row.getId()
                                    : result instanceof com.ses.entity.Invoice row ? row.getId()
                                    : source instanceof Long number ? number : null;
                            String type = invocation.getThis() instanceof com.ses.service.EngineerService ? "engineer-availability"
                                    : invocation.getThis() instanceof com.ses.service.ProjectService ? "project"
                                    : invocation.getThis() instanceof com.ses.service.ContractService ? "contract-status" : "invoice-status";
                            if (id != null) producers.getObject().changed(type, id);
                        }
                        return result;
                    } catch (RuntimeException | Error failure) {
                        throw failure;
                    } catch (Throwable failure) {
                        throw new java.lang.reflect.UndeclaredThrowableException(failure);
                    }
                }));
    }
}
