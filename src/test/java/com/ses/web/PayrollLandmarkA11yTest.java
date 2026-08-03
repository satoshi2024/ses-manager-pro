package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = com.ses.controller.page.PayrollPageController.class)
@DisplayName("給与明細 ARIA landmark & 構造化アクセシビリティテスト (R3-020)")
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
