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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
class ContractDocumentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    @BeforeEach
    void allowFullScope() {
        when(dataScopeService.isScoped()).thenReturn(false);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(service.getById(10L)).thenReturn(document());
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

        mockMvc.perform(get("/api/contract-documents/10/download"))
                .andExpect(status().isOk())
                // red: 現行はno-storeもContent-Dispositionも付けない
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment; filename=")));
    }

    @Test
    @WithMockUser(roles = {"HR"})
    void HRはmanualSyncを実行できてしまう() throws Exception {
        // red: HRは参照のみでありsync更新APIは403になるべきだが、現行は@PreAuthorizeで許可している
        mockMvc.perform(post("/api/contract-documents/10/sync").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
