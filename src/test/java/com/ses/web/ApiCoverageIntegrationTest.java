package com.ses.web;

import com.ses.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Sql(scripts = {"/sql/engineer-schema-h2.sql", "/sql/api-coverage-data.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {"ai.provider=rule"})
class ApiCoverageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testExportApi() throws Exception {
        mockMvc.perform(get("/api/engineers/export?fullName=Test&status=Bench&employmentType=正社員"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/contracts/export?status=稼動中&customerName=Test&keyword=Java"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/dashboard/revenue-export?fiscalYear=2024"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testCsvApi() throws Exception {
        mockMvc.perform(get("/api/engineers/export-csv?fullName=Test&status=Bench"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/projects/export-csv?projectName=Test&status=募集中"))
                .andExpect(status().isOk());

        String csvContent = "氏名,カナ,イニシャル,性別,生年月日,国籍,最寄り駅,都道府県,路線,雇用形態,ステータス,希望単価,稼動可能日,経験年数,日本語レベル,要約,備考\n" +
                "山田 太郎,ヤマダ タロウ,Y.T,男性,1990-01-01,日本,東京,東京都,JR,正社員,Bench,800000,2024-04-01,5,ネイティブ,,備考";
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes());
        
        mockMvc.perform(multipart("/api/engineers/import-csv").file(file).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testAiApi() throws Exception {
        // AI Matching endpoints
        String matchEngBody = "{\"engineerId\": 1}";
        mockMvc.perform(post("/api/ai/match/engineer-to-projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(matchEngBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ai/matching/project/1")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testAutocompleteApi() throws Exception {
        mockMvc.perform(get("/api/autocomplete/customers?q=Test"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/autocomplete/engineers?q=Test"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/autocomplete/projects?q=Test"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testInvoiceApi() throws Exception {
        mockMvc.perform(get("/api/invoices?page=1&size=10&invoiceNo=INV&customerId=1"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testUserApi() throws Exception {
        mockMvc.perform(get("/api/users?page=1&size=10&keyword=admin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testProposalApi() throws Exception {
        mockMvc.perform(get("/api/proposals/kanban"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "管理者")
    void testEngineerCareerApi() throws Exception {
        mockMvc.perform(get("/api/engineers/1/careers"))
                .andExpect(status().isOk());
    }
}
