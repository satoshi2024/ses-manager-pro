package com.ses.controller.page;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class PageRenderingTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @CsvSource({
            "/sales-performance, sales-performance.js",
            "/ai/matching, ai.js",
            "/analytics/availability-calendar, availability-calendar.js",
            "/engineer/list, engineer.js",
            "/customer/list, customer.js",
            "/project/list, project.js",
            "/contract/list, contract.js",
            "/work-record, work-record.js",
            "/invoice, invoice.js"
    })
    @WithMockUser(username = "admin", roles = {"管理者"})
    public void testPageRendering(String url, String expectedJsFile) throws Exception {
        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(expectedJsFile)))
                // Verify no raw thymeleaf tags leaked into the output
                .andExpect(content().string(not(containsString("th:text="))));
    }
}
