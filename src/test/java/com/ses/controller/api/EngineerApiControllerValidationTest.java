package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Engineer;
import com.ses.service.EngineerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 要員登録APIの入力バリデーション検証（P8 Task1）
 */
@WebMvcTest(EngineerApiController.class)
class EngineerApiControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EngineerService engineerService;

    /** 氏名・雇用形態が揃った正常な要員は登録成功（code=200） */
    @Test
    @WithMockUser
    void save_validEngineer_returns200() throws Exception {
        when(engineerService.save(any(Engineer.class))).thenReturn(true);

        Engineer engineer = Engineer.builder()
                .fullName("山田太郎")
                .employmentType("正社員")
                .status("Bench")
                .expectedUnitPrice(new BigDecimal("60"))
                .build();

        mockMvc.perform(post("/api/engineers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 氏名が空の場合は code=400 と日本語メッセージを返す */
    @Test
    @WithMockUser
    void save_blankFullName_returns400() throws Exception {
        Engineer engineer = Engineer.builder()
                .fullName("")
                .employmentType("正社員")
                .status("Bench")
                .build();

        mockMvc.perform(post("/api/engineers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("氏名は必須です")));
    }

    /** 希望単価が負値の場合は code=400 を返す */
    @Test
    @WithMockUser
    void save_negativeUnitPrice_returns400() throws Exception {
        Engineer engineer = Engineer.builder()
                .fullName("山田太郎")
                .employmentType("正社員")
                .status("Bench")
                .expectedUnitPrice(new BigDecimal("-1"))
                .build();

        mockMvc.perform(post("/api/engineers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(engineer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
