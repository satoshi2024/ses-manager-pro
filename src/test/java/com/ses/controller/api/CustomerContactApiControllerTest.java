package com.ses.controller.api;

import com.ses.dto.customer.CustomerContactDto;
import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.service.CustomerContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** T050の画面/CSV同一PIIマスクを確認する。 */
@WebMvcTest(CustomerContactApiController.class)
class CustomerContactApiControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private CustomerContactService customerContactService;

    @Test
    void screenAndCsvUseTheSameMaskedDto() throws Exception {
        CustomerContactDto dto = new CustomerContactDto();
        dto.setId(1L);
        dto.setCustomerId(10L);
        dto.setName("担当者");
        dto.setEmail("t***@example.com");
        dto.setPhone("***5678");
        dto.setValidFrom(LocalDate.of(2026, 1, 1));
        dto.setStatus("有効");
        when(customerContactService.list(eq(10L), any())).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/customers/10/contacts").with(authentication("default")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("t***@example.com"));

        mockMvc.perform(get("/api/customers/10/contacts/export").with(authentication("default")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("t***@example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("taro@example.com"))));
    }

    private RequestPostProcessor authentication(String tenantId) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("contact-test-user");
        user.setPassword("password");
        user.setRole("営業");
        user.setStatus(1);
        user.setTenantId(tenantId);
        LoginUser principal = new LoginUser(user,
                java.util.List.of(new SimpleGrantedAuthority("ROLE_営業")));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
