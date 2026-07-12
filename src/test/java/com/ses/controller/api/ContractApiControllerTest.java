package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Contract;
import com.ses.service.ContractService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契約APIのテスト（P8 Task9）: 登録・必須項目バリデーション。
 */
@WebMvcTest(ContractApiController.class)
class ContractApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ContractService contractService;

    @Test
    @WithMockUser
    void create_必須が揃えば200() throws Exception {
        Contract c = new Contract();
        c.setEngineerId(1L);
        c.setProjectId(2L);
        c.setCustomerId(3L);
        c.setStartDate(LocalDate.of(2026, 7, 1));
        c.setSellingPrice(new BigDecimal("80"));
        c.setCostPrice(new BigDecimal("60"));
        mockMvc.perform(post("/api/contracts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void create_要員ID欠落は400() throws Exception {
        // engineerId を欠落させる
        Map<String, Object> body = Map.of(
                "projectId", 2, "customerId", 3,
                "startDate", "2026-07-01", "sellingPrice", 80, "costPrice", 60);
        mockMvc.perform(post("/api/contracts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
