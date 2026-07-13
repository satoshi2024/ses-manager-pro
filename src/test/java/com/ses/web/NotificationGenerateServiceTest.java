package com.ses.web;

import com.ses.BaseIntegrationTest;
import com.ses.service.NotificationGenerateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

public class NotificationGenerateServiceTest extends BaseIntegrationTest {

    @Autowired
    private NotificationGenerateService notificationGenerateService;

    @Test
    @Sql(scripts = {"/sql/engineer-schema-h2.sql", "/sql/api-coverage-data.sql"})
    public void testGenerateAll() {
        notificationGenerateService.generateAll();
    }
}
