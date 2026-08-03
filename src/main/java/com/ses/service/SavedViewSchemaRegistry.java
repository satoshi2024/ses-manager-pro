package com.ses.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 保存ビューのJSON/フィールド名の allowlist スキーマ検証
 */
@Component
public class SavedViewSchemaRegistry {

    private static final Pattern SAFE_FIELD_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * ページ毎の許可フィールド定義
     */
    private static final Map<String, Set<String>> ALLOWED_FIELDS_BY_PAGE = new HashMap<>();

    static {
        Set<String> commonFields = Set.of(
                "id", "name", "status", "createdAt", "updatedAt", "deletedFlag",
                "pageKey", "pageSize", "sortField", "sortOrder", "keyword"
        );

        Set<String> engineerFields = new HashSet<>(commonFields);
        engineerFields.addAll(List.of(
                "fullName", "fullNameKana", "initialName", "gender", "employmentType",
                "expectedUnitPrice", "experienceYears", "prefecture", "railwayCompany",
                "salesUserId", "skill", "unitPrice", "contractStatus", "availabilityDate",
                "costCenterId", "organizationId", "skills", "remarks", "riskLevel", "skillId"
        ));
        Set<String> unmodifiableEngineer = Collections.unmodifiableSet(engineerFields);
        ALLOWED_FIELDS_BY_PAGE.put("engineer", unmodifiableEngineer);
        ALLOWED_FIELDS_BY_PAGE.put("engineer_list", unmodifiableEngineer);

        Set<String> customerFields = new HashSet<>(commonFields);
        customerFields.addAll(List.of("customerCode", "companyName", "industry", "prefecture", "salesUserId", "commercialFlow", "trustLevel"));
        Set<String> unmodifiableCustomer = Collections.unmodifiableSet(customerFields);
        ALLOWED_FIELDS_BY_PAGE.put("customer", unmodifiableCustomer);
        ALLOWED_FIELDS_BY_PAGE.put("customer_list", unmodifiableCustomer);

        Set<String> projectFields = new HashSet<>(commonFields);
        projectFields.addAll(List.of("projectCode", "projectName", "customerId", "minUnitPrice", "maxUnitPrice", "unitPriceMin", "unitPriceMax", "commercialFlow", "remoteType"));
        Set<String> unmodifiableProject = Collections.unmodifiableSet(projectFields);
        ALLOWED_FIELDS_BY_PAGE.put("project", unmodifiableProject);
        ALLOWED_FIELDS_BY_PAGE.put("project_list", unmodifiableProject);

        Set<String> contractFields = new HashSet<>(commonFields);
        contractFields.addAll(List.of("contractNo", "contractCode", "engineerId", "customerId", "salesUserId", "startDate", "endDate", "sellingPrice", "costPrice", "billingAmount"));
        Set<String> unmodifiableContract = Collections.unmodifiableSet(contractFields);
        ALLOWED_FIELDS_BY_PAGE.put("contract", unmodifiableContract);
        ALLOWED_FIELDS_BY_PAGE.put("contract_list", unmodifiableContract);

        Set<String> invoiceFields = new HashSet<>(commonFields);
        invoiceFields.addAll(List.of("invoiceNo", "customerId", "billingMonth", "issuedDate", "dueDate", "totalAmount", "subtotal", "tax", "taxRate"));
        Set<String> unmodifiableInvoice = Collections.unmodifiableSet(invoiceFields);
        ALLOWED_FIELDS_BY_PAGE.put("invoice", unmodifiableInvoice);
        ALLOWED_FIELDS_BY_PAGE.put("invoice_list", unmodifiableInvoice);
    }

    /**
     * 単一フィールド名が安全かつ許可された形式であるか検証
     */
    public void validateFieldName(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return;
        }
        if (!SAFE_FIELD_PATTERN.matcher(fieldName).matches()) {
            throw new BusinessException(400, "無効なフィールド名が含まれています: " + fieldName);
        }
    }

    /**
     * JSON文字列内のキー・フィールド名を構造的に検証
     */
    public void validateJsonContent(String pageKey, String jsonContent) {
        if (!StringUtils.hasText(pageKey)) {
            throw new BusinessException(400, "pageKeyは必須です");
        }
        Set<String> allowedFields = ALLOWED_FIELDS_BY_PAGE.get(pageKey);
        if (allowedFields == null) {
            throw new BusinessException(400, "未登録のpageKeyです: " + pageKey);
        }
        if (!StringUtils.hasText(jsonContent)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            validateJsonNode(root, allowedFields);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "保存ビューJSONの形式が不正です");
        }
    }

    private void validateJsonNode(JsonNode node, Set<String> allowedFields) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                validateFieldName(key);
                if (!allowedFields.contains(key)) {
                    throw new BusinessException(400, "許可されていないフィールド名が含まれています: " + key);
                }
                validateJsonNode(entry.getValue(), allowedFields);
            }
        } else if (node.isArray()) {
            for (JsonNode elem : node) {
                validateJsonNode(elem, allowedFields);
            }
        } else if (node.isTextual()) {
            String val = node.asText();
            if (val.contains(";") || val.contains("'") || val.contains("--") || val.contains("<") || val.contains("*")) {
                throw new BusinessException(400, "無効な文字が含まれています: " + val);
            }
        }
    }
}
