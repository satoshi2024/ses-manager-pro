package com.ses.service.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;

import java.math.BigDecimal;
import java.util.Map;

/** 対象adapterが共有するpayload読み書き。業務ルールは各既存serviceへ委譲する。 */
public final class ApprovalPayloads {
    private ApprovalPayloads() {
    }

    public static Map<String, Object> read(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            throw BusinessException.of(409, "error.approval.invalidState");
        }
    }

    public static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static Long longValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.valueOf(String.valueOf(value));
    }

    public static BigDecimal decimal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }
}
