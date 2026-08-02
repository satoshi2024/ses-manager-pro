package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.contract.ContractListDto;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractRenewalService;
import com.ses.service.ContractService;
import com.ses.service.RenewalCalendarService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.MfaService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.security.PersistentSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractApiController.class)
@DisplayName("契約一覧サーバーサイドページング")
class ContractPaginationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ContractService contractService;
    @MockBean private ContractRenewalService contractRenewalService;
    @MockBean private RenewalCalendarService renewalCalendarService;
    @MockBean private ContractMapper contractMapper;
    @MockBean private DataScopeService dataScopeService;
    @MockBean private OrganizationScopeService organizationScopeService;
    @MockBean private AuthorizationService authorizationService;
    @MockBean private MfaService mfaService;
    @MockBean private PersistentSessionService persistentSessionService;

    private long datasetTotal;

    @BeforeEach
    void setUp() {
        datasetTotal = 147L;
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        lenient().when(organizationScopeService.intersectWithDataScope(any(), any()))
                .thenAnswer(invocation -> {
                    java.util.Collection<Long> organizationIds = invocation.getArgument(0);
                    return organizationIds == null ? Set.of() : Set.copyOf(organizationIds);
                });
        when(authorizationService.isAllowed(any(), any())).thenReturn(true);
        when(persistentSessionService.validateAndTouch(any(), any())).thenReturn(true);
        when(contractMapper.selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Page<ContractListDto> page = invocation.getArgument(0);
            String status = invocation.getArgument(1);
            @SuppressWarnings("unchecked")
            List<Long> allowedIds = invocation.getArgument(12);
            long total = allowedIds != null ? allowedIds.size() : ("稼動中".equals(status) ? 43L : datasetTotal);
            page.setTotal(total);

            List<Long> ids;
            if (allowedIds != null) {
                ids = new ArrayList<>(allowedIds);
                ids.sort(Comparator.reverseOrder());
            } else {
                ids = LongStream.iterate(total, value -> value - 1).limit(total).boxed().toList();
            }
            long offset = (page.getCurrent() - 1) * page.getSize();
            int from = (int) Math.min(offset, ids.size());
            int to = (int) Math.min(offset + page.getSize(), ids.size());
            page.setRecords(ids.subList(from, to).stream().map(id -> {
                ContractListDto dto = new ContractListDto();
                dto.setId(id);
                dto.setContractNo("R3-CON-" + String.format("%03d", id));
                return dto;
            }).toList());
            return page;
        });
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    @DisplayName("既定20件で147件を8ページに分割する")
    void defaultPageSize_is20And147RowsHaveEightPages() throws Exception {
        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(147))
                .andExpect(jsonPath("$.data.pages").value(8))
                .andExpect(jsonPath("$.data.records.length()").value(20))
                .andExpect(jsonPath("$.data.records[0].id").value(147))
                .andExpect(jsonPath("$.data.records[19].id").value(128));
    }

    @ParameterizedTest(name = "total={0}, page={1}, records={2}")
    @CsvSource({
            "0,1,0",
            "1,1,1",
            "100,1,100",
            "101,2,1",
            "147,2,47"
    })
    @WithMockUser(username = "admin", roles = "管理者")
    @DisplayName("0/1/100/101/147件の境界で欠落しない")
    void rowCountBoundaries_areReachable(long total, long current, int expectedRecords) throws Exception {
        datasetTotal = total;
        var actions = mockMvc.perform(get("/api/contracts")
                        .param("current", String.valueOf(current))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(total))
                .andExpect(jsonPath("$.data.current").value(current))
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.records.length()").value(expectedRecords));
        if (expectedRecords > 0) {
            actions.andExpect(jsonPath("$.data.records[" + (expectedRecords - 1) + "].id").value(1));
        }
    }

    @ParameterizedTest(name = "current={0}, size={1} -> current={2}, size={3}")
    @CsvSource({
            "-5,-1,1,20",
            "0,0,1,20",
            "1,101,1,100",
            "2,1000,2,100"
    })
    @WithMockUser(username = "admin", roles = "管理者")
    @DisplayName("負数・0・100件超のpage指定を正規化する")
    void unsafePageParameters_areNormalized(long current, long size, long expectedCurrent, long expectedSize)
            throws Exception {
        mockMvc.perform(get("/api/contracts")
                        .param("current", String.valueOf(current))
                        .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(expectedCurrent))
                .andExpect(jsonPath("$.data.size").value(expectedSize));
    }

    @Test
    @WithMockUser(username = "manager", roles = "マネージャー")
    @DisplayName("マネージャーの37件はscope後total 37・2ページになる")
    void managerScope_totalIsCalculatedAfterScope() throws Exception {
        Set<Long> visibleIds = LongStream.rangeClosed(1, 37).boxed().collect(java.util.stream.Collectors.toSet());
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedContractIds(any())).thenReturn(visibleIds);
        when(organizationScopeService.intersectWithDataScope(eq(visibleIds), any())).thenReturn(visibleIds);

        mockMvc.perform(get("/api/contracts").param("current", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(37))
                .andExpect(jsonPath("$.data.pages").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(17))
                .andExpect(jsonPath("$.data.records[16].id").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(contractMapper).selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), idsCaptor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(37, idsCaptor.getValue().size());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    @DisplayName("filter後のtotalをページング母数にする")
    void filteredTotal_isReturnedByPageQuery() throws Exception {
        mockMvc.perform(get("/api/contracts").param("status", "稼動中").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(43))
                .andExpect(jsonPath("$.data.pages").value(3));
    }

    @Test
    @WithMockUser(username = "manager", roles = "マネージャー")
    @DisplayName("scope 0件でも正規化済み空ページを返す")
    void emptyScope_returnsNormalizedEmptyPageWithoutMapperCall() throws Exception {
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedContractIds(any())).thenReturn(Set.of());
        when(organizationScopeService.intersectWithDataScope(eq(Set.of()), any())).thenReturn(Set.of());

        mockMvc.perform(get("/api/contracts").param("current", "0").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(0));
        verify(contractMapper, never()).selectPageWithNames(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }
}
