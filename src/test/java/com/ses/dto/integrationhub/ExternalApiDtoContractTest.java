package com.ses.dto.integrationhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExternalApiDtoContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void projectDtoContainsOnlyApprovedFields() throws Exception {
        JsonNode json = objectMapper.valueToTree(new ExternalApiProject("public-project", "ACTIVE",
                LocalDate.of(2026, 1, 1), null, "public-customer"));

        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("publicProjectId", "status", "startDate", "publicCustomerId"), fields);
        assertFalse(json.has("id"));
        assertFalse(json.has("projectName"));
        assertFalse(json.has("customerName"));
        assertFalse(json.has("unitPriceMin"));
        assertFalse(json.has("cost"));
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
}
