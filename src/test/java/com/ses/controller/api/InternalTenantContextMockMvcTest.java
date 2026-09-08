package com.ses.controller.api;

import com.ses.config.LoginUser;
import com.ses.config.OidcLoginUser;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.Certification;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.mapper.NotificationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.certification.CertificationMasterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 内部HTTPリクエストの認証tenant固定と資格master・通知のtenant境界を検証する。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InternalTenantContextMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private CertificationMasterService certificationMasterService;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private OidcSecurityProperties oidcSecurityProperties;

    private Certification tenantAMaster;
    private Long principalId = 1L;

    @BeforeEach
    void setUp() {
        tenantAMaster = AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            Certification input = new Certification();
            input.setDisplayName("HTTP資格-" + UUID.randomUUID());
            input.setIssuerDisplay("IPA");
            input.setExternalCode("HTTP-" + UUID.randomUUID());
            input.setExpiryType("NONE");
            return certificationMasterService.createMaster(input, principalId);
        });

        AccountingTenantContextHolder.runWithTenant("tenant-a", () -> {
            Notification notification = new Notification();
            notification.setTenantId("tenant-a");
            notification.setRecipientUserId(principalId);
            notification.setType("SYSTEM");
            notification.setTitle("tenant-a通知");
            notification.setMessage("tenant-a通知本文");
            notification.setDedupeKey("HTTP-TENANT-" + UUID.randomUUID());
            notification.setCreatedAt(LocalDateTime.now());
            notificationMapper.insert(notification);
            return null;
        });
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
        oidcSecurityProperties.setTenantId("default");
    }

    @Test
    void tenantAの資格masterは認証tenantで取得できbodyやheaderでtenantを変更できない() throws Exception {
        mockMvc.perform(get("/api/certification-learning-gap/masters/certifications")
                        .with(authentication("tenant-a", "HR"))
                        .param("tenantId", "tenant-b")
                        .header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(tenantAMaster.getId()))
                .andExpect(jsonPath("$.data[0].displayName").value(tenantAMaster.getDisplayName()));

        mockMvc.perform(post("/api/certification-learning-gap/masters/certifications")
                        .with(authentication("tenant-a", "HR"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", "tenant-b",
                                "displayName", "越境資格",
                                "issuerDisplay", "IPA",
                                "externalCode", "FORGED-" + UUID.randomUUID(),
                                "expiryType", "NONE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void tenantBはtenantAの資格masterを読めず通知も見えない() throws Exception {
        mockMvc.perform(get("/api/certification-learning-gap/masters/certifications/" + tenantAMaster.getId())
                        .with(authentication("tenant-b", "HR")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/notifications")
                        .with(authentication("tenant-b", "管理者")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("tenant-a通知本文"))));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    @Test
    void OIDCtenant不一致とtenant欠落principalはHTTP403で業務処理を実行しない() throws Exception {
        oidcSecurityProperties.setTenantId("tenant-a");
        OidcUser delegate = org.mockito.Mockito.mock(OidcUser.class);
        SysUser oidcUser = user("tenant-b", "HR");
        OidcLoginUser oidcPrincipal = new OidcLoginUser(oidcUser, delegate,
                List.of(new SimpleGrantedAuthority("ROLE_HR")));
        mockMvc.perform(get("/api/certification-learning-gap/masters/certifications")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .authentication(new UsernamePasswordAuthenticationToken(oidcPrincipal, null,
                                        oidcPrincipal.getAuthorities()))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("OIDC_TENANT_MISMATCH")));

        mockMvc.perform(get("/api/certification-learning-gap/masters/certifications")
                        .with(authentication(null, "HR")))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("TENANT_CONTEXT_REQUIRED")));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId());
    }

    private RequestPostProcessor authentication(String tenantId, String role) {
        SysUser user = user(tenantId, role);
        LoginUser principal = new LoginUser(user,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private SysUser user(String tenantId, String role) {
        SysUser user = new SysUser();
        user.setId(principalId);
        user.setUsername("http-tenant-user");
        user.setPassword("password");
        user.setRole(role);
        user.setStatus(1);
        user.setTenantId(tenantId);
        return user;
    }
}
