package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.servicedesk.CustomerQbrCreateRequest;
import com.ses.dto.servicedesk.CustomerQbrUpdateRequest;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CustomerQbrApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private Long testCustomerId;

    @BeforeEach
    void setUp() {
        Customer c = Customer.builder()
                .companyName("QBRテスト顧客-" + UUID.randomUUID().toString().substring(0, 6))
                .build();
        customerMapper.insert(c);
        testCustomerId = c.getId();
    }

    @Test
    @WithMockUser(username = "1", roles = {"管理者"})
    @DisplayName("定例会(QBR)の作成・取得・更新・削除のCRUDが正常に動作すること")
    void testQbrCrudFlow() throws Exception {
        CustomerQbrCreateRequest req = CustomerQbrCreateRequest.builder()
                .customerId(testCustomerId)
                .meetingDate(LocalDate.now())
                .title("2026年第2四半期 定例振り返り")
                .agenda("稼働状況確認、来期増員のご相談")
                .minutes("要員評価は良好。来期1名増員の意向あり。")
                .actionItems("スキルシート送付: 来週金曜まで")
                .csatScore(5)
                .attendees("顧客側: 山田部長、弊社: 佐藤")
                .build();

        // 1. 作成 (POST)
        String resJson = mockMvc.perform(post("/api/customer-success/qbrs")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.title").value("2026年第2四半期 定例振り返り"))
                .andReturn().getResponse().getContentAsString();

        Long qbrId = objectMapper.readTree(resJson).path("data").path("id").asLong();

        // 2. 詳細取得 (GET)
        mockMvc.perform(get("/api/customer-success/qbrs/" + qbrId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(qbrId))
                .andExpect(jsonPath("$.data.agenda").value("稼働状況確認、来期増員のご相談"))
                .andExpect(jsonPath("$.data.minutes").value("要員評価は良好。来期1名増員の意向あり。"));

        // 3. 一覧検索 (GET)
        mockMvc.perform(get("/api/customer-success/qbrs")
                        .param("customerId", String.valueOf(testCustomerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1));

        // 4. 更新 (PUT)
        CustomerQbrUpdateRequest updateReq = CustomerQbrUpdateRequest.builder()
                .meetingDate(LocalDate.now())
                .title("2026年第2四半期 定例振り返り (修正版)")
                .agenda("更新済みアジェンダ")
                .minutes("更新済み議事録")
                .actionItems("更新済みアクション")
                .csatScore(4)
                .attendees("参加者更新")
                .build();

        mockMvc.perform(put("/api/customer-success/qbrs/" + qbrId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 削除 (DELETE)
        mockMvc.perform(delete("/api/customer-success/qbrs/" + qbrId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 削除後の取得は 404
        mockMvc.perform(get("/api/customer-success/qbrs/" + qbrId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
