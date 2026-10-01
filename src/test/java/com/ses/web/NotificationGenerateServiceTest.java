package com.ses.web;

import com.ses.BaseIntegrationTest;
import com.ses.service.NotificationGenerateService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

public class NotificationGenerateServiceTest extends BaseIntegrationTest {

    @org.junit.jupiter.api.BeforeEach
    void bindDefaultTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @org.junit.jupiter.api.AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Autowired
    private NotificationGenerateService notificationGenerateService;

    @Test
    @Sql(scripts = {"/sql/engineer-schema-h2.sql", "/sql/api-coverage-data.sql"})
    public void testGenerateAll() {
        notificationGenerateService.generateAll();
    }
}
