package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Proposal;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.MailService;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 提案APIのテスト（P8 Task9）: かんばん一覧・登録。
 */
@WebMvcTest(ProposalApiController.class)
class ProposalApiControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProposalService proposalService;
    @MockBean
    private EngineerService engineerService;
    @MockBean
    private ProjectService projectService;
    @MockBean
    private CustomerService customerService;
    @MockBean
    private MailService mailService;
    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;
    @MockBean
    private com.ses.service.skillsheet.SkillSheetGenerator skillSheetGenerator;
    @MockBean
    private com.ses.service.FileStorageService fileStorageService;

    @Test
    @WithMockUser
    void kanban_一覧は200() throws Exception {
        when(proposalService.getKanbanList()).thenReturn(List.of());
        mockMvc.perform(get("/api/proposals/kanban"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void save_登録は200() throws Exception {
        when(proposalService.save(any())).thenReturn(true);
        Proposal p = new Proposal();
        p.setEngineerId(1L);
        p.setProjectId(2L);
        mockMvc.perform(post("/api/proposals").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(p)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void kanban_重複チェックは200() throws Exception {
        when(proposalService.findActiveDuplicates(1L, 2L, null)).thenReturn(List.of());
        mockMvc.perform(get("/api/proposals/duplicate-check")
                        .param("engineerId", "1")
                        .param("customerId", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser
    void exportSkillSheet_権限なしの場合は404() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedProposalIds()).thenReturn(java.util.Set.of(2L)); // ID 1 is not allowed
        
        ProposalApiController.SkillSheetExportRequest req = new ProposalApiController.SkillSheetExportRequest();
        req.setFormat("PDF");
        
        mockMvc.perform(post("/api/proposals/1/skill-sheet/export").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                // GlobalExceptionHandler は BusinessException のコードを HTTP ステータスへ写像する
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
