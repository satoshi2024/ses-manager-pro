package com.ses.controller.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Customer;
import com.ses.entity.SalesActivity;
import com.ses.dto.salesactivity.SalesActivityUpdateRequest;
import com.ses.service.CustomerService;
import com.ses.service.SalesActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class SalesActivityApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SalesActivityService salesActivityService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private ObjectMapper objectMapper;

    private Customer testCustomer;

    @BeforeEach
    void setUp() {
        testCustomer = new Customer();
        testCustomer.setCompanyName("Test Company");
        customerService.save(testCustomer);
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testCreateActivity() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setActivityType("商談");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("Test Title");
        activity.setContent("Test Content");

        mockMvc.perform(post("/api/customers/" + testCustomer.getId() + "/activities")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(activity)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        assertEquals(1, salesActivityService.count());
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testGetActivities() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("訪問");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("Test Visit");
        salesActivityService.save(activity);

        mockMvc.perform(get("/api/customers/" + testCustomer.getId() + "/activities")
                .param("type", "訪問"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].title", is("Test Visit")));
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testCompleteActivity() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("電話");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("To Complete");
        salesActivityService.save(activity);

        mockMvc.perform(put("/api/customers/" + testCustomer.getId() + "/activities/" + activity.getId() + "/complete")
                .with(csrf()).param("version", String.valueOf(activity.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        SalesActivity updated = salesActivityService.getById(activity.getId());
        assertEquals(1, updated.getCompletedFlag());
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void staleActivityUpdateIsRejectedWithConflict() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("電話");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("Initial");
        salesActivityService.save(activity);

        SalesActivityUpdateRequest request = new SalesActivityUpdateRequest();
        request.setVersion(activity.getVersion());
        request.setActivityType("電話");
        request.setActivityDate(LocalDate.now());
        request.setTitle("First writer");
        String payload = objectMapper.writeValueAsString(request);

        mockMvc.perform(put("/api/customers/" + testCustomer.getId() + "/activities/" + activity.getId())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)));

        mockMvc.perform(put("/api/customers/" + testCustomer.getId() + "/activities/" + activity.getId())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(409)));
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testDeleteActivity() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("メール");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("To Delete");
        salesActivityService.save(activity);

        mockMvc.perform(delete("/api/customers/" + testCustomer.getId() + "/activities/" + activity.getId())
                .with(csrf()).param("version", String.valueOf(activity.getVersion())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", is(true)));

        SalesActivity deleted = salesActivityService.getById(activity.getId());
        assertNull(deleted);
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void crossCustomerActivityMutationIsRejected() throws Exception {
        Customer anotherCustomer = new Customer();
        anotherCustomer.setCompanyName("Another Company");
        customerService.save(anotherCustomer);

        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("電話");
        activity.setActivityDate(LocalDate.now());
        activity.setTitle("Protected");
        salesActivityService.save(activity);

        mockMvc.perform(delete("/api/customers/" + anotherCustomer.getId() + "/activities/" + activity.getId())
                        .with(csrf()).param("version", String.valueOf(activity.getVersion())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is(404)));

        assertNotNull(salesActivityService.getById(activity.getId()));
    }

    @Test
    @WithMockUser(username = "test", roles = {"営業"})
    void testGetFollowUps() throws Exception {
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(testCustomer.getId());
        activity.setActivityType("商談");
        activity.setActivityDate(LocalDate.now().minusDays(5));
        activity.setNextActionDate(LocalDate.now().minusDays(1));
        activity.setTitle("Needs Follow Up");
        activity.setCompletedFlag(0);
        salesActivityService.save(activity);

        mockMvc.perform(get("/api/customers/follow-ups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data[0].title", is("Needs Follow Up")));
    }

    @Test
    @WithMockUser(username = "admin", roles = {"管理者"})
    void testCustomerSummaryWinRateZeroDenominator() throws Exception {
        mockMvc.perform(get("/api/customers/" + testCustomer.getId() + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.data.projectCount", is(0)))
                .andExpect(jsonPath("$.data.proposalCount", is(0)))
                .andExpect(jsonPath("$.data.winRate").isEmpty());
    }
}
