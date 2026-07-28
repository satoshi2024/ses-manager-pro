package com.ses.controller.api;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.security.ScopeVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** DataScope設定の変更がcommit後にのみDashboard cache generationへ反映されることを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
class SystemConfigScopeInvalidationTest {

    @Autowired
    private SystemConfigApiController controller;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private ScopeVersionRegistry scopeVersionRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetToFalse() {
        new TransactionTemplate(transactionManager).execute(status -> {
            SystemConfig current = systemConfigMapper.selectById("scope.sales-own-data-only");
            if (current == null || !"false".equals(current.getConfigValue())) {
                controller.update(List.of(config("false")));
            }
            return null;
        });
    }

    @Test
    void false_true_falseの往復でgenerationが毎回更新されrollbackでは更新されない() {
        long before = scopeVersionRegistry.current();
        commitUpdate("true");
        assertEquals(before + 1, scopeVersionRegistry.current());

        commitUpdate("false");
        assertEquals(before + 2, scopeVersionRegistry.current());

        long beforeRollback = scopeVersionRegistry.current();
        new TransactionTemplate(transactionManager).execute(status -> {
            controller.update(List.of(config("true")));
            status.setRollbackOnly();
            return null;
        });
        assertEquals(beforeRollback, scopeVersionRegistry.current(), "rollbackではgenerationを進めない");
    }

    private void commitUpdate(String value) {
        new TransactionTemplate(transactionManager).execute(status -> {
            controller.update(List.of(config(value)));
            return null;
        });
    }

    private SystemConfig config(String value) {
        return new SystemConfig("scope.sales-own-data-only", value, "test");
    }
}
