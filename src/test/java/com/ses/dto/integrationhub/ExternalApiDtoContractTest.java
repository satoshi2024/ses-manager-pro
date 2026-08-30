package com.ses.dto.integrationhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.controller.externalapi.ExternalApiReadController;
import com.ses.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiDtoContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void projectDtoContainsOnlyApprovedFields() throws Exception {
        JsonNode json = objectMapper.valueToTree(new ExternalApiProject("public-project", "ACTIVE",
                LocalDate.of(2026, 1, 1), null, "public-customer"));

        assertFields(json, Set.of("publicProjectId", "status", "startDate", "publicCustomerId"));
    }

    @Test
    void allApprovedResourceDtosHaveExactAllowListsAndNoDeniedFields() {
        assertFields(objectMapper.valueToTree(new ExternalApiEngineerAvailability(
                        "public-engineer", "AVAILABLE", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 2, 1), java.util.List.of("JAVA"))),
                Set.of("publicEngineerId", "availabilityStatus", "availableFrom", "availableTo", "skillTagCode"));
        assertFields(objectMapper.valueToTree(new ExternalApiContractStatus(
                        "public-contract", "public-project", "ACTIVE", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31), "RENEW")),
                Set.of("publicContractId", "publicProjectId", "status", "startDate", "endDate", "renewalStatus"));
        assertFields(objectMapper.valueToTree(new ExternalApiInvoiceStatus(
                        "public-invoice", "public-contract", "OUTSTANDING", LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 2, 1), Instant.parse("2026-02-01T00:00:00Z"), "OUTSTANDING")),
                Set.of("publicInvoiceId", "publicContractId", "status", "issueDate", "dueDate", "paidAt",
                        "settlementStatus"));
    }

    @Test
    void controllerExposesExactlyTheElevenApprovedGetOnlyPaths() {
        Set<String> paths = new HashSet<>();
        long mappedMethods = 0;
        for (Method method : ExternalApiReadController.class.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping == null) continue;
            mappedMethods++;
            paths.addAll(Arrays.asList(mapping.value()));
            assertFalse(method.getReturnType().getPackageName().equals("com.ses.entity"));
        }
        assertEquals(11, mappedMethods);
        assertEquals(Set.of(
                "/engineer-availability", "/engineer-availability/{publicEngineerId}",
                "/projects", "/projects/{publicProjectId}", "/projects/count",
                "/contract-statuses", "/contract-statuses/{publicContractId}", "/contract-statuses/count",
                "/invoice-statuses", "/invoice-statuses/{publicInvoiceId}", "/invoice-statuses/count"), paths);
    }

    @Test
    void publicDtoDoesNotSerializeInternalEntityFields() throws Exception {
        JsonNode publicJson = objectMapper.valueToTree(new ExternalApiProject(
                "public-project", "ACTIVE", LocalDate.of(2026, 1, 1), null, "public-customer"));
        Project internalEntity = new Project();
        internalEntity.setProjectName("internal-project");
        internalEntity.setCustomerId(42L);
        JsonNode entityJson = objectMapper.valueToTree(internalEntity);

        assertFalse(publicJson.has("id"));
        assertFalse(publicJson.has("customerId"));
        assertFalse(publicJson.has("projectName"));
        assertFalse(publicJson.has("unitPrice"));
        assertFalse(publicJson.has("cost"));
        assertFalse(publicJson.has("rawBody"));
        assertFalse(publicJson.has("description"));
        assertTrue(entityJson.has("projectName"));
        assertTrue(entityJson.has("customerId"));
    }

    @Test
    void listAndCountContractsDoNotExposeInternalPagingFields() throws Exception {
        JsonNode list = objectMapper.valueToTree(new ExternalApiListResponse<>(java.util.List.of(
                new ExternalApiProject("public-project", "ACTIVE", null, null, null)),
                "v1.encrypted", false, java.time.Instant.parse("2026-08-30T00:00:00Z")));
        JsonNode count = objectMapper.valueToTree(new ExternalApiCountResponse(1L,
                java.time.Instant.parse("2026-08-30T00:00:00Z")));

        assertFalse(list.has("current"));
        assertFalse(list.has("size"));
        assertFalse(list.has("total"));
        assertFalse(count.has("page"));
        assertFalse(count.has("internalId"));
    }

    private void assertFields(JsonNode json, Set<String> expected) {
        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(expected, fields);
        for (String denied : Set.of("id", "internalId", "projectName", "customerName", "customerId",
                "unitPrice", "unitPriceMin", "cost", "grossProfit", "accountNumber", "password", "token",
                "secret", "rawBody", "providerRawBody", "stackTrace", "sql")) {
            assertFalse(json.has(denied), "公開DTOに禁止fieldが存在します: " + denied);
        }
    }
}
