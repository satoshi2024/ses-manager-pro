package com.ses.controller.api;

import com.ses.dto.customer.CustomerContactDto;
import com.ses.service.CustomerContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
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
    @WithMockUser(roles = "営業")
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

        mockMvc.perform(get("/api/customers/10/contacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("t***@example.com"));

        mockMvc.perform(get("/api/customers/10/contacts/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("t***@example.com")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("taro@example.com"))));
    }
}
