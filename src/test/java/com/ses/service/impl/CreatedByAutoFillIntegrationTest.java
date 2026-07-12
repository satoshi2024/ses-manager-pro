package com.ses.service.impl;

import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.SysUser;
import com.ses.service.EngineerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * created_by 自動設定（MetaObjectHandler）のH2結合テスト（P8 Task2）。
 * ログイン中ユーザーがいる場合はcreatedByが自動設定され、
 * 認証文脈が無い場合はnullのままであることを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class CreatedByAutoFillIntegrationTest {

    @Autowired
    private EngineerService engineerService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(long userId) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setUsername("tester");
        user.setStatus(1);
        LoginUser principal = new LoginUser(user, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES));
    }

    @Test
    void save_ログイン中はcreatedByがログインユーザーIDで自動設定される() {
        loginAs(42L);

        Engineer e = new Engineer();
        e.setFullName("監査太郎");
        e.setEmploymentType("正社員");
        e.setStatus("Bench");
        assertTrue(engineerService.save(e), "保存が成功すること");

        Engineer saved = engineerService.getById(e.getId());
        assertEquals(42L, saved.getCreatedBy(), "createdByがログインユーザーIDで埋まること");
    }

    @Test
    void save_認証文脈が無い場合createdByはnullのまま() {
        // ログインしない（SecurityContextは空）
        Engineer e = new Engineer();
        e.setFullName("匿名太郎");
        e.setEmploymentType("正社員");
        e.setStatus("Bench");
        assertTrue(engineerService.save(e), "保存が成功すること");

        Engineer saved = engineerService.getById(e.getId());
        assertNull(saved.getCreatedBy(), "認証が無ければcreatedByはnullのまま");
    }
}
