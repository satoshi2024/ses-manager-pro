package com.ses.controller.api;

import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.LeadService;
import com.ses.service.security.CrmScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LeadApiController.class)
class LeadApiControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LeadService leadService;
    @MockBean private SysUserMapper sysUserMapper;
    @MockBean private CrmScopeService crmScopeService;

    @Test
    @WithMockUser(roles = "営業")
    void assignees_returnsOnlyOwnersInCrmScope() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setRealName("営業A");
        when(crmScopeService.hasFullAccess()).thenReturn(false);
        when(crmScopeService.allowedOwnerIds(any())).thenReturn(Set.of(1L));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));

        mockMvc.perform(get("/api/crm/leads/assignees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1))
                .andExpect(jsonPath("$.data[0].name").value("営業A"));
        verify(sysUserMapper).selectList(any());
    }

    @Test
    @WithMockUser(roles = "営業")
    void assignees_scopeWithoutOwners_returnsEmptyWithoutQuery() throws Exception {
        when(crmScopeService.hasFullAccess()).thenReturn(false);
        when(crmScopeService.allowedOwnerIds(any())).thenReturn(Set.of());

        mockMvc.perform(get("/api/crm/leads/assignees"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
