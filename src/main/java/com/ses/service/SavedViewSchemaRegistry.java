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
        Set<String> commonFields = Set.of("id", "name", "status", "createdAt", "updatedAt", "deletedFlag");
        
        Set<String> engineerFields = new HashSet<>(commonFields);
        engineerFields.addAll(List.of("prefecture", "station", "salesUserId", "skill", "unitPrice", "contractStatus", "availabilityDate"));
        ALLOWED_FIELDS_BY_PAGE.put("engineer", Collections.unmodifiableSet(engineerFields));

        Set<String> customerFields = new HashSet<>(commonFields);
        customerFields.addAll(List.of("customerCode", "companyName", "industry", "prefecture", "salesUserId"));
        ALLOWED_FIELDS_BY_PAGE.put("customer", Collections.unmodifiableSet(customerFields));

        Set<String> projectFields = new HashSet<>(commonFields);
        projectFields.addAll(List.of("projectCode", "projectName", "customerId", "minUnitPrice", "maxUnitPrice"));
        ALLOWED_FIELDS_BY_PAGE.put("project", Collections.unmodifiableSet(projectFields));

        Set<String> contractFields = new HashSet<>(commonFields);
        contractFields.addAll(List.of("contractCode", "engineerId", "customerId", "salesUserId", "startDate", "endDate", "billingAmount"));
        ALLOWED_FIELDS_BY_PAGE.put("contract", Collections.unmodifiableSet(contractFields));

        Set<String> invoiceFields = new HashSet<>(commonFields);
        invoiceFields.addAll(List.of("invoiceNo", "customerId", "issueDate", "dueDate", "totalAmount", "taxRate"));
        ALLOWED_FIELDS_BY_PAGE.put("invoice", Collections.unmodifiableSet(invoiceFields));
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
        if (!StringUtils.hasText(jsonContent)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            validateJsonNode(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "保存ビューJSONの形式が不正です");
        }
    }

    private void validateJsonNode(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                validateFieldName(entry.getKey());
                validateJsonNode(entry.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode elem : node) {
                validateJsonNode(elem);
            }
        } else if (node.isTextual()) {
            String val = node.asText();
            // 配列の要素がフィールド名（表示列一覧等）として送られる場合があるため簡易チェック
            if (val.startsWith("col_") || SAFE_FIELD_PATTERN.matcher(val).matches()) {
                // OK
            } else if (val.contains(";") || val.contains("'") || val.contains("--") || val.contains("<")) {
                throw new BusinessException(400, "無効な文字列が含まれています: " + val);
            }
        }
    }
}
