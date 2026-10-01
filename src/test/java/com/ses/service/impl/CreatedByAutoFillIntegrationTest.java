package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.DisableDefaultTenantTestContext;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * created_by 自動設定（MetaObjectHandler）のH2結合テスト（P8 Task2）。
 * ログイン中ユーザーがいる場合はcreatedByが自動設定され、
 * 認証文脈が無い書込みはtenant境界で拒否されることを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@Transactional
class CreatedByAutoFillIntegrationTest {

    @Autowired
    private EngineerService engineerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContext() {
        AccountingTenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void loginAs(long userId) {
        TenantTestSecurity.bindAs(userId, "tester", "default", "管理者");
    }

    @Test
    void save_ログイン中はcreatedByがログインユーザーIDで自動設定される() {
        loginAs(42L);
        TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L, 42L);

        Engineer e = new Engineer();
        e.setTenantId("default");
        e.setLegalEntityId(1L);
        e.setFullName("監査太郎");
        e.setEmploymentType("正社員");
        e.setStatus("Bench");
        assertTrue(engineerService.save(e), "保存が成功すること");

        Engineer saved = engineerService.getById(e.getId());
        assertEquals(42L, saved.getCreatedBy(), "createdByがログインユーザーIDで埋まること");
    }

    @Test
    @DisableDefaultTenantTestContext
    void save_認証文脈が無い場合はtenant境界で拒否される() {
        // ログインしない（SecurityContextは空）
        Engineer e = new Engineer();
        e.setTenantId("default");
        e.setLegalEntityId(1L);
        e.setFullName("匿名太郎");
        e.setEmploymentType("正社員");
        e.setStatus("Bench");
        assertThrows(BusinessException.class, () -> engineerService.save(e),
                "認証が無い書込みはtenant境界で拒否されること");
    }
}
