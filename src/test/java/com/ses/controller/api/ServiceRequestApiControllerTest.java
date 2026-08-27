package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.dto.servicedesk.ServiceRequestUpdateRequest;
import com.ses.entity.Customer;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.CustomerMapper;
import com.ses.service.servicedesk.ServiceRequestService;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class ServiceRequestApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ServiceRequestService serviceRequestService;

    private Customer testCustomer;
    private ServiceRequest testRequest;

    @BeforeEach
    void setUp() {
        testCustomer = new Customer();
        testCustomer.setCompanyName("株式会社APIテスト顧客");
        customerMapper.insert(testCustomer);

        ServiceRequestCreateRequest req = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("CONTRACT")
                .priority("P1")
                .subject("APIテスト件名")
                .description("APIテスト本文")
                .build();
        testRequest = serviceRequestService.createRequest(req, 1L, false, null);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("GET /api/service-desk/requests - 一覧取得が成功すること")
    void testListRequests() throws Exception {
        mockMvc.perform(get("/api/service-desk/requests")
                        .param("current", "1")
                        .param("size", "10")
                        .param("keyword", "APIテスト")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("GET /api/service-desk/requests/{id} - 詳細取得が成功すること")
    void testGetRequest() throws Exception {
        mockMvc.perform(get("/api/service-desk/requests/" + testRequest.getId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(testRequest.getId()))
                .andExpect(jsonPath("$.data.subject").value("APIテスト件名"))
                .andExpect(jsonPath("$.data.currentSlaClock").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("POST /api/service-desk/requests - 新規起票が成功すること")
    void testCreateRequest() throws Exception {
        ServiceRequestCreateRequest newReq = ServiceRequestCreateRequest.builder()
                .customerId(testCustomer.getId())
                .category("SYSTEM")
                .priority("P0")
                .subject("新規障害起票")
                .description("サーバー高負荷アラート")
                .build();

        mockMvc.perform(post("/api/service-desk/requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.requestNo").isString());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("PUT /api/service-desk/requests/{id} - 属性更新が成功すること")
    void testUpdateRequest() throws Exception {
        ServiceRequestUpdateRequest updateReq = ServiceRequestUpdateRequest.builder()
                .subject("更新された件名")
                .description("更新された本文")
                .priority("P2")
                .category("BILLING")
                .build();

        mockMvc.perform(put("/api/service-desk/requests/" + testRequest.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/service-desk/requests/" + testRequest.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").value("更新された件名"))
                .andExpect(jsonPath("$.data.priority").value("P2"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("POST /api/service-desk/requests/{id}/status - ステータス変更が成功すること")
    void testChangeStatus() throws Exception {
        ServiceRequestStatusChangeRequest statusReq = ServiceRequestStatusChangeRequest.builder()
                .toStatus("IN_PROGRESS")
                .reason("調査着手")
                .build();

        mockMvc.perform(post("/api/service-desk/requests/" + testRequest.getId() + "/status")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/service-desk/requests/" + testRequest.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.firstResponseAt").exists());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("POST /api/service-desk/requests/{id}/comments - コメント投稿が成功すること")
    void testAddComment() throws Exception {
        ServiceCommentCreateRequest commentReq = ServiceCommentCreateRequest.builder()
                .commentText("調査結果を共有します。")
                .visibility("PORTAL_VISIBLE")
                .build();

        mockMvc.perform(post("/api/service-desk/requests/" + testRequest.getId() + "/comments")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.commentText").value("調査結果を共有します。"));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    @DisplayName("GET /api/service-desk/requests/policies - SLAポリシー一覧取得が成功すること")
    void testGetPolicies() throws Exception {
        mockMvc.perform(get("/api/service-desk/requests/policies")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }
}
