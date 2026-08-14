package com.ses.controller.api;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.ses.entity.ContractDocument;
import com.ses.mapper.ContractTemplateMapper;
import com.ses.service.ContractDocumentService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ContractDocumentApiController のAPI契約・認可test（HFP-02-01〜07）。
 * 認可境界はfull context（method security有効）で検証する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql("/sql/engineer-schema-h2.sql")
class ContractDocumentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockBean
    private ContractDocumentService service;
    @MockBean
    private ContractTemplateMapper templates;
    @MockBean
    private DataScopeService dataScopeService;
    @MockBean
    private OrganizationScopeService organizationScopeService;
    @MockBean
    private com.ses.service.cloudsign.CloudSignSyncService cloudSignSyncService;
    @MockBean
    private com.ses.service.cloudsign.CloudSignArtifactService cloudSignArtifactService;

    @BeforeEach
    void allowFullScope() {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(service.getById(10L)).thenReturn(document());
        // 共有H2は先行@Sqlクラスがm_menuを再作成しcontract-document seedを失う場合があるため、
        // MenuPermissionFilter用のmenu/role_menuを明示的に復元する（実本番はV20 seedが存在）
        jdbcTemplate.update("DELETE FROM t_role_menu WHERE menu_id IN "
                + "(SELECT id FROM m_menu WHERE menu_key='contract-document')");
        jdbcTemplate.update("DELETE FROM m_menu WHERE menu_key='contract-document'");
        jdbcTemplate.update("INSERT INTO m_menu(menu_key, menu_name, path_prefix, api_prefix, sort_order) "
                + "VALUES('contract-document','契約書・電子署名','/contract','/api/contract-documents',67)");
        for (String role : new String[]{"管理者", "営業", "HR", "マネージャー"}) {
            jdbcTemplate.update("INSERT INTO t_role_menu(role, menu_id) "
                    + "SELECT ?, id FROM m_menu WHERE menu_key='contract-document'", role);
        }
    }

    private ContractDocument document() {
        ContractDocument d = new ContractDocument();
        d.setId(10L);
        d.setContractId(100L);
        d.setTemplateId(1L);
        d.setRenderedHtml("<p>契約本文</p>");
        d.setPdfPath("/uploads/contracts/100/document-1.pdf");
        d.setSignedPdfPath("/uploads/contracts/100/signed-10.pdf");
        d.setCertificatePath("/uploads/contracts/100/certificate-10.dat");
        d.setStatus("下書き");
        d.setRecipientName("マスク宛先");
        d.setRecipientEmail("recipient-masked@example.invalid");
        return d;
    }

    @Test
    @WithMockUser(roles = {"管理者"})
    void listはentityを直接返しrenderedHtmlとstoragePathを露出する() throws Exception {
        LambdaQueryChainWrapper<ContractDocument> wrapper = mock(LambdaQueryChainWrapper.class);
        when(service.lambdaQuery()).thenReturn(wrapper);
        when(wrapper.eq(any(), any())).thenReturn(wrapper);
        when(wrapper.list()).thenReturn(List.of(document()));

        mockMvc.perform(get("/api/contract-documents/contract/100"))
                .andExpect(status().isOk())
                // red: 現行はentityを返すためrenderedHtml/pdfPath等がJSONに露出する
                .andExpect(jsonPath("$.data[0].renderedHtml").doesNotExist())
                .andExpect(jsonPath("$.data[0].pdfPath").doesNotExist())
                .andExpect(jsonPath("$.data[0].signedPdfPath").doesNotExist())
                .andExpect(jsonPath("$.data[0].certificatePath").doesNotExist())
                // red: listはno-storeであるべきだが現行はcache headerを付けない
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @WithMockUser(roles = {"管理者"})
    void downloadにnoStoreとContentDispositionのattachmentがない() throws Exception {
        when(service.download(10L)).thenReturn("%PDF-1.4 dummy".getBytes());

        mockMvc.perform(get("/api/contract-documents/10/artifacts/source"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=")));
    }

    @Test
    @WithMockUser(roles = {"HR"})
    void HRはmanualSyncを実行できてしまう() throws Exception {
        // HFP-02-05でHRを拒否済み: 参照のみ（HFP-02-AC-08-01）
        mockMvc.perform(post("/api/contract-documents/10/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"HR"})
    void HRは送信queueを受け付けられない() throws Exception {
        mockMvc.perform(post("/api/contract-documents/10/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractNo\":\"C-1\",\"templateVersion\":1,\"recipientName\":\"x\","
                                + "\"recipientEmail\":\"x@example.invalid\",\"title\":\"t\",\"languageCode\":\"ja\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"要員"})
    void 要員は契約書APIへアクセスできない() throws Exception {
        mockMvc.perform(get("/api/contract-documents/contract/100"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/contract-documents/10/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"営業"})
    void 営業は送信queueとsyncを実行できる() throws Exception {
        com.ses.entity.ContractDocument queued = new com.ses.entity.ContractDocument();
        queued.setId(10L);
        queued.setOperationId("op-1");
        queued.setDispatchState("QUEUED");
        when(service.queueSend(eq(10L), any())).thenReturn(queued);

        mockMvc.perform(post("/api/contract-documents/10/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractNo\":\"C-1\",\"templateVersion\":1,\"recipientName\":\"x\","
                                + "\"recipientEmail\":\"x@example.invalid\",\"title\":\"t\",\"languageCode\":\"ja\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatchState").value("QUEUED"));

        mockMvc.perform(post("/api/contract-documents/10/sync").with(csrf()))
                .andExpect(status().isOk());
        verify(cloudSignSyncService).syncDocument(10L);
    }

    @Test
    @WithMockUser(roles = {"管理者"})
    void scope外の契約は404で存在を漏らさない() throws Exception {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(dataScopeService.allowedContractIds()).thenReturn(java.util.Set.of(999L));

        mockMvc.perform(get("/api/contract-documents/contract/100"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"管理者"})
    void sendはqueue受付であり送信完了と偽装しない() throws Exception {
        com.ses.entity.ContractDocument queued = new com.ses.entity.ContractDocument();
        queued.setId(10L);
        queued.setOperationId("op-2");
        queued.setDispatchState("QUEUED");
        when(service.queueSend(eq(10L), any())).thenReturn(queued);

        mockMvc.perform(post("/api/contract-documents/10/send")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contractNo\":\"C-1\",\"templateVersion\":1,\"recipientName\":\"x\","
                                + "\"recipientEmail\":\"x@example.invalid\",\"title\":\"t\",\"languageCode\":\"ja\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dispatchState").value("QUEUED"))
                .andExpect(jsonPath("$.data.operationId").value("op-2"));
        // queue受付であり、provider送信（artifact回収等の同期処理）は行われない
        verify(cloudSignSyncService, never()).syncDocument(any());
        verifyNoInteractions(cloudSignArtifactService);
    }
}
