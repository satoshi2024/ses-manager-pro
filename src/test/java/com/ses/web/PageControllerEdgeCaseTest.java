package com.ses.web;

import com.ses.BaseIntegrationTest;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Page Controller & Custom Error Controller Edge Cases")
public class PageControllerEdgeCaseTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Directly hit /error without attributes should return 500 error page")
    void testDirectErrorEndpoint() throws Exception {
        mockMvc.perform(get("/error"))
                .andExpect(status().isOk()) // The controller returns 200 OK by default for custom error rendering, though the status attribute is 500
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 500))
                .andExpect(model().attributeExists("title", "message", "icon"));
    }

    @Test
    @DisplayName("Hit /error with simulated 404 status code")
    void testErrorEndpointWith404() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 404))
                .andExpect(model().attribute("title", "ページが見つかりません"));
    }

    @Test
    @DisplayName("Hit /error with simulated 403 status code")
    void testErrorEndpointWith403() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 403))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 403))
                .andExpect(model().attribute("title", "アクセス権限がありません"));
    }

    @Test
    @DisplayName("Hit /error with simulated 400 status code")
    void testErrorEndpointWith400() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 400))
                .andExpect(status().isOk())
                .andExpect(view().name("error"))
                .andExpect(model().attribute("status", 400))
                .andExpect(model().attribute("title", "リクエストに誤りがあります"));
    }

    @Test
    @DisplayName("Hit /error for API request (JSON) should return JSON")
    void testErrorEndpointForApiJson() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/something")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("Hit /error with X-Requested-With header should return JSON")
    void testErrorEndpointForAjax() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @DisplayName("Request random non-existent endpoint should map to 404")
    @WithMockUser
    void testRandomNonExistentEndpoint() throws Exception {
        // Without mocked user, it might redirect to login (302) first.
        // With mock user, it hits the non-existent endpoint and returns 404.
        mockMvc.perform(get("/this-endpoint-does-not-exist-" + System.currentTimeMillis()))
                .andExpect(status().isNotFound()); // Or it forwards to /error depending on Spring Boot config
    }

    @Test
    @DisplayName("Unauthenticated request to protected endpoint should redirect to login")
    void testUnauthenticatedRequestToProtectedEndpoint() throws Exception {
        // Assuming /dashboard or similar requires authentication
        mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("Unauthenticated request to non-existent endpoint should redirect to login (or 404 if public)")
    void testUnauthenticatedRequestToNonExistentEndpoint() throws Exception {
        // Typically, spring security protects /** and redirects to login, or 401.
        mockMvc.perform(get("/another-non-existent-endpoint"))
                .andExpect(status().is3xxRedirection());
    }
}
