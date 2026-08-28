package com.ses.service.pwa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** PWA commandのclient/server共通hash形式。object keyを再帰的にsortし、array順は保持する。 */
@Component
public class PwaCanonicalizer {
    private final ObjectMapper objectMapper;

    public PwaCanonicalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(String screen, String month, Integer baseVersion, JsonNode payload) {
        ObjectNode command = objectMapper.createObjectNode();
        if (baseVersion == null) command.putNull("baseVersion");
        else command.put("baseVersion", baseVersion);
        if (month == null) command.putNull("month");
        else command.put("month", month);
        command.set("payload", payload == null ? objectMapper.nullNode() : payload);
        if (screen == null) command.putNull("screen");
        else command.put("screen", screen);
        return sha256(canonical(command));
    }

    public String hash(String operation, String screen, String month, Integer baseVersion, JsonNode payload) {
        ObjectNode command = objectMapper.createObjectNode();
        if (baseVersion == null) command.putNull("baseVersion");
        else command.put("baseVersion", baseVersion);
        if (month == null) command.putNull("month");
        else command.put("month", month);
        if (operation == null) command.putNull("operation");
        else command.put("operation", operation);
        command.set("payload", payload == null ? objectMapper.nullNode() : payload);
        if (screen == null) command.putNull("screen");
        else command.put("screen", screen);
        return sha256(canonical(command));
    }

    public String canonical(JsonNode node) {
        if (node == null || node.isNull()) return "null";
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            StringBuilder json = new StringBuilder("{");
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) json.append(',');
                String name = names.get(i);
                json.append(quote(name)).append(':').append(canonical(node.get(name)));
            }
            return json.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) json.append(',');
                json.append(canonical(node.get(i)));
            }
            return json.append(']').toString();
        }
        return node.toString();
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("PWA commandのcanonical化に失敗しました", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
