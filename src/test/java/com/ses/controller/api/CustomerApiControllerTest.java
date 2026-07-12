package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Customer;
import com.ses.service.ContractService;
import com.ses.service.CustomerService;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import com.ses.service.SalesActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 顧客APIのテスト（P8 Task9）: 一覧・登録・バリデーションエラー。
 */
@WebMvcTest(CustomerApiController.class)
class CustomerApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CustomerService customerService;
    @MockBean
    private ProjectService projectService;
    @MockBean
    private ProposalService proposalService;
    @MockBean
    private ContractService contractService;
    @MockBean
    private SalesActivityService salesActivityService;

    @Test
    @WithMockUser
    void page_一覧は200() throws Exception {
        when(customerService.page(any(), any())).thenReturn(new Page<>());
        mockMvc.perform(get("/api/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void save_正常は200() throws Exception {
        when(customerService.save(any())).thenReturn(true);
        Customer c = new Customer();
        c.setCompanyName("株式会社テスト");
        mockMvc.perform(post("/api/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void save_会社名空は400() throws Exception {
        Map<String, Object> body = Map.of("contactPerson", "担当A");
        mockMvc.perform(post("/api/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
