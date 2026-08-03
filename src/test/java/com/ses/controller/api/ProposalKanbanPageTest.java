package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.service.CustomerService;
import com.ses.service.EngineerService;
import com.ses.service.FileStorageService;
import com.ses.service.MailService;
import com.ses.service.OpportunityService;
import com.ses.service.ProjectService;
import com.ses.service.ProposalService;
import com.ses.service.security.CrmScopeService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.skillsheet.SkillSheetGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProposalApiController.class)
@DisplayName("提案Kanban paged APIテスト (R3-013)")
class ProposalKanbanPageTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProposalService proposalService;
    @MockBean
    private OpportunityService opportunityService;
    @MockBean
    private EngineerService engineerService;
    @MockBean
    private ProjectService projectService;
    @MockBean
    private CustomerService customerService;
    @MockBean
    private MailService mailService;
    @MockBean
    private DataScopeService dataScopeService;
    @MockBean
    private OrganizationScopeService organizationScopeService;
    @MockBean
    private CrmScopeService crmScopeService;
    @MockBean
    private SkillSheetGenerator skillSheetGenerator;
    @MockBean
    private FileStorageService fileStorageService;

    @Test
    @WithMockUser(roles = "営業")
    @DisplayName("kanban/pageはstatusごとのPage<ProposalKanbanDto>を返す")
    void getKanbanPageReturnsPagedColumn() throws Exception {
        Page<ProposalKanbanDto> page = new Page<>(1, 20, 83);
        ProposalKanbanDto dto = new ProposalKanbanDto();
        dto.setId(10L);
        dto.setStatus("書類選考中");
        dto.setEngineerName("提案 要員");
        page.setRecords(List.of(dto));

        when(proposalService.getKanbanPage(eq("書類選考中"), eq(1L), eq(20L), eq(null)))
                .thenReturn(page);

        mockMvc.perform(get("/api/proposals/kanban/page")
                        .param("status", "書類選考中")
                        .param("current", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(83))
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.records[0].engineerName").value("提案 要員"));
    }
}
