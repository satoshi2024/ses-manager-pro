package com.ses.web;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FE-A10（feature-enablement-console design §15.2・FE-RQ-011・FE-AC-011-01）: 画面「機能の開放」を Thymeleaf で描画し（実際の layout・
 * sidebar・message bundle）、管理者には sidebar に menu が出て画面が4つの tab・履歴の絞込み（{@code .card > .card-body >
 * form#searchForm}）・登録の modal・同梱の script を持ち、管理者以外（営業・HR・マネージャー）には menu が出ないことを確かめる。
 * 文言は key の名前のまま出ない（{@code spring.messages.use-code-as-default-message} で key が出る漏れを検出する）。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureEnablementTemplateRenderingTest {

    private static final Pattern RAW_KEY = Pattern.compile("featureEnablement\\.[A-Za-z0-9_.]+");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;

    private FeatureEnablementH2Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
    }

    @Test
    void 管理者には画面のtabと絞込みとmodalとscriptとmenuを描画し文言はkeyのまま出さない() throws Exception {
        FeatureEnablementActor admin = fixture.admin();

        String html = mockMvc.perform(get("/feature-enablement").with(as(admin, "管理者")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"fe-units-tab\"", "id=\"fe-requests-tab\"", "id=\"fe-signoffs-tab\"",
                "id=\"fe-events-tab\"", "id=\"featureEnablementModal\"", "id=\"featureEnablementBundleModal\"",
                "id=\"featureEnablementDetailModal\"", "/js/modules/feature-enablement.js", "href=\"/feature-enablement\"",
                "機能の開放");
        assertThat(html).containsPattern(
                "(?s)<div class=\"card bg-card border-dark shadow-sm mb-3\">\\s*<div class=\"card-body\">\\s*<form id=\"searchForm\"");
        Matcher raw = RAW_KEY.matcher(html.replace("/js/modules/feature-enablement.js", ""));
        assertThat(raw.find() ? raw.group() : null).as("message key がそのまま出ている").isNull();
        assertThat(html).doesNotContain("<datalist");
    }

    @Test
    void 管理者以外の画面のsidebarには機能の開放のmenuを出さない() throws Exception {
        for (String role : List.of("営業", "HR", "マネージャー")) {
            FeatureEnablementActor user = fixture.user(role);
            String html = mockMvc.perform(get("/dashboard").with(as(user, role)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html).as(role).contains("id=\"sidebar\"").doesNotContain("href=\"/feature-enablement\"");
        }
        FeatureEnablementActor admin = fixture.admin();
        assertThat(mockMvc.perform(get("/dashboard").with(as(admin, "管理者"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("href=\"/feature-enablement\"");
    }

    private static RequestPostProcessor as(FeatureEnablementActor actor, String role) {
        SysUser user = new SysUser();
        user.setId(actor.userId());
        user.setUsername(actor.username());
        user.setPassword("pw");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(FeatureEnablementH2Fixture.TENANT);
        user.setDeletedFlag(0);
        LoginUser principal = new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
