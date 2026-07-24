package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Contract;
import com.ses.service.ContractRenewalService;
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
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.contract.ContractListDto;
import com.ses.mapper.ContractMapper;

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
    @MockBean
    private ContractRenewalService contractRenewalService;
    @MockBean
    private com.ses.service.RenewalCalendarService renewalCalendarService;
    @MockBean
    private ContractMapper contractMapper;
    @MockBean
    private com.ses.service.security.DataScopeService dataScopeService;

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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void page_パラメータなしで全件取得() throws Exception {
        Page<ContractListDto> mockPage = new Page<>(1, 100);
        when(contractMapper.selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void page_検索パラメータ複合で絞れる_contractNo部分一致() throws Exception {
        Page<ContractListDto> mockPage = new Page<>(1, 100);
        when(contractMapper.selectPageWithNames(any(), eq("稼動中"), eq(10L), any(), any(), eq("C-001"), any(), any(), any(), any(), any(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/contracts?status=稼動中&customerId=10&contractNo=C-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void page_engineerNameが解決される() throws Exception {
        Page<ContractListDto> mockPage = new Page<>(1, 100);
        ContractListDto dto = new ContractListDto();
        dto.setEngineerName("テスト 太郎");
        mockPage.setRecords(List.of(dto));
        
        when(contractMapper.selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].engineerName").value("テスト 太郎"));
    }

    @Test
    @WithMockUser
    void page_削除済み要員の契約はengineerNameがnullで返る() throws Exception {
        Page<ContractListDto> mockPage = new Page<>(1, 100);
        ContractListDto dto = new ContractListDto();
        dto.setEngineerName(null);
        mockPage.setRecords(List.of(dto));
        
        when(contractMapper.selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mockPage);

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].engineerName").isEmpty());
    }

    @Test
    @WithMockUser
    void update_状態項目は専用API以外では無視する() throws Exception {
        Contract c = new Contract();
        c.setEngineerId(1L);
        c.setProjectId(2L);
        c.setCustomerId(3L);
        c.setStartDate(LocalDate.of(2026, 7, 1));
        c.setSellingPrice(new BigDecimal("80"));
        c.setCostPrice(new BigDecimal("60"));
        c.setStatus("稼動中");

        mockMvc.perform(put("/api/contracts/10").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(contractService).updateWithBusinessRules(argThat(updated -> updated.getId().equals(10L)
                && updated.getStatus() == null));
    }

    @Test
    @WithMockUser
    void getById_契約詳細を返す() throws Exception {
        Contract c = new Contract();
        c.setId(10L);
        c.setContractNo("C-202607-0001");
        c.setEngineerId(1L);
        when(contractService.getById(10L)).thenReturn(c);

        mockMvc.perform(get("/api/contracts/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractNo").value("C-202607-0001"));
    }

    @Test
    @WithMockUser
    void changeStatus_解約日を渡すとサービスへ引き継がれる() throws Exception {
        Map<String, Object> body = Map.of("status", "解約", "cancelDate", "2026-07-15");

        mockMvc.perform(put("/api/contracts/10/status").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(contractService).changeStatus(eq(10L), eq("解約"), eq(LocalDate.of(2026, 7, 15)));
    }
}
