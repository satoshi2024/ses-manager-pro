package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 給与明細 ARIA landmark & 構造化アクセシビリティテスト。
 *
 * <p>HFP-01-008で拡張: 単一main、role=region、aria-live、label/for、
 * 接続buttonは管理者だけ（HRに表示しない）、取得buttonはtype=submit。</p>
 */
@WebMvcTest(controllers = com.ses.controller.page.PayrollPageController.class)
@DisplayName("給与明細 ARIA landmark & 構造化アクセシビリティテスト")
class PayrollLandmarkA11yTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("給与画面にrole='region'とaria-live属性が含まれ、main要素が1つのみであること")
    void payrollPageContainsAriaLandmarksAndSingleMain() throws Exception {
        MvcResult result = mockMvc.perform(get("/payroll"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"region\"")))
                .andExpect(content().string(containsString("aria-label=\"給与明細照会\"")))
                .andExpect(content().string(containsString("aria-live=\"polite\"")))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        int mainCount = occurrences(html, "<main");
        assertEquals(1, mainCount, "描画後のHTMLでmainタグが1つだけ存在すること");
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("入力要素はlabel/forで関連付けられ、取得buttonはtype=submit")
    void payrollPageHasFormLabelsAndSubmitButtons() throws Exception {
        MvcResult result = mockMvc.perform(get("/payroll"))
                .andExpect(status().isOk())
                .andReturn();
        String html = result.getResponse().getContentAsString();

        for (String id : new String[]{"statementYear", "statementMonth", "statementType",
                "linkEngineerId", "linkEmployeeId"}) {
            assertTrue(html.contains("for=\"" + id + "\""),
                    "label for=" + id + " が存在すること");
            assertTrue(html.contains("id=\"" + id + "\""),
                    "input/select id=" + id + " が存在すること");
        }
        assertTrue(html.contains("<button type=\"submit\" class=\"btn btn-primary\" id=\"statementFetchBtn\">取得</button>"),
                "取得buttonはtype=submitで二重送信制御対象であること");
        assertTrue(html.contains("id=\"statementDetailModal\""), "明細modalが存在すること");
        assertTrue(html.contains("aria-labelledby=\"statementDetailModalLabel\""), "明細modalにaria-labelledby");
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("管理者には接続・再接続・解除buttonが描画される")
    void adminSeesConnectionButtons() throws Exception {
        mockMvc.perform(get("/payroll"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"connectBtn\"")))
                .andExpect(content().string(containsString("id=\"reconnectBtn\"")))
                .andExpect(content().string(containsString("id=\"disconnectBtn\"")));
    }

    @Test
    @WithMockUser(roles = "HR")
    @DisplayName("HRには接続操作buttonが描画されない（参照のみ）")
    void hrDoesNotSeeConnectionButtons() throws Exception {
        mockMvc.perform(get("/payroll"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"connectBtn\""))))
                .andExpect(content().string(not(containsString("id=\"disconnectBtn\""))))
                .andExpect(content().string(containsString("id=\"statementFetchBtn\"")));
    }

    @Test
    @WithMockUser(roles = "管理者")
    @DisplayName("モバイル向けにcol-12のフォーム行が含まれる（390px対応）")
    void mobileFriendlyFormLayout() throws Exception {
        mockMvc.perform(get("/payroll"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("col-12 col-md-4")))
                .andExpect(content().string(containsString("col-6 col-md-2")))
                .andExpect(content().string(containsString("table-responsive")));
    }

    private int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
