package com.ses.service.impl;

import com.ses.entity.ContractDocument;
import com.ses.service.CloudSignClient;
import com.ses.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component
public class CloudSignClientImpl implements CloudSignClient {

    @Value("${cloudsign.enabled:false}")
    private boolean enabled;
    
    @Value("${cloudsign.base-url:}")
    private String baseUrl;
    
    @Value("${cloudsign.token:}")
    private String token;
    
    private final RestTemplate rest;

    public CloudSignClientImpl(@Qualifier("saasRestTemplate") RestTemplate rest) {
        this.rest = rest;
    }

    private void ensure() {
        if (!enabled || baseUrl == null || baseUrl.isBlank() || token == null || token.isBlank()) {
            throw BusinessException.of("error.contract.document.cloudsignNotConfigured");
        }
    }

    @Override
    public Result send(ContractDocument d) {
        ensure();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setContentType(MediaType.APPLICATION_JSON);
            
            Map<String, Object> body = Map.of(
                    "title", "SES契約 " + d.getContractId(),
                    "name", d.getRecipientName(),
                    "email", d.getRecipientEmail()
            );
            
            ResponseEntity<Map> r = rest.postForEntity(baseUrl + "/documents", new HttpEntity<>(body, h), Map.class);
            Map m = r.getBody() == null ? Map.of() : r.getBody();
            String id = Objects.toString(m.get("id"), "");
            
            if (id.isBlank()) {
                throw new IllegalStateException("missing id");
            }
            
            return new Result(id, Objects.toString(m.get("file_id"), null), "送信中", null, null);
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.cloudsignFailed", e.getMessage());
        }
    }

    @Override
    public Result status(String id) {
        ensure();
        if (id == null || id.isBlank()) {
            throw BusinessException.of("error.contract.document.cloudsignInvalid");
        }
        try {
            ResponseEntity<Map> r = rest.exchange(baseUrl + "/documents/" + id, HttpMethod.GET, auth(), Map.class);
            Map m = r.getBody() == null ? Map.of() : r.getBody();
            
            String statusStr = Objects.toString(m.getOrDefault("status", "確認中"), "確認中");
            String fileId = Objects.toString(m.get("file_id"), null);
            byte[] signedPdf = null;
            
            if ("締結済".equals(statusStr) && fileId != null) {
                try {
                    ResponseEntity<byte[]> fileRes = rest.exchange(baseUrl + "/documents/" + id + "/files/" + fileId, HttpMethod.GET, auth(), byte[].class);
                    signedPdf = fileRes.getBody();
                } catch (Exception ignored) {
                }
            }
            return new Result(id, fileId, statusStr, signedPdf, null);
        } catch (Exception e) {
            throw BusinessException.of("error.contract.document.cloudsignFailed", e.getMessage());
        }
    }

    private HttpEntity<Void> auth() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return new HttpEntity<>(h);
    }
}
