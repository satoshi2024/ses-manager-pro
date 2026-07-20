package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.entity.Engineer;
import com.ses.entity.FreeeConnection;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Service @RequiredArgsConstructor
public class FreeeIntegrationServiceImpl extends ServiceImpl<FreeeConnectionMapper, FreeeConnection> implements FreeeIntegrationService {
    private final FreeeConnectionMapper connectionMapper; private final FreeeEmployeeLinkMapper linkMapper;
    private final EngineerMapper engineerMapper; private final RestTemplate restTemplate; private final ObjectMapper objectMapper;
    @Value("${freee.client-id:}") private String clientId; @Value("${freee.client-secret:}") private String clientSecret;
    @Value("${freee.redirect-uri:http://localhost:8080/integrations/freee/callback}") private String redirectUri;
    @Value("${freee.api-base-url:https://api.freee.co.jp}") private String apiBase;
    @Value("${freee.token-encryption-key:change-me-change-me-change-me-1234}") private String encryptionKey;
    @Value("${spring.profiles.active:dev}") private String activeProfile;
    @PostConstruct void validateConfig(){
        if (activeProfile.contains("prod") && encryptionKey.startsWith("change-me")) {
            throw new IllegalStateException("freee.token-encryption-key must be configured");
        }
    }

    public String authorizationUrl(String state) { return UriComponentsBuilder.fromUriString(apiBase + "/oauth/authorize")
            .queryParam("client_id", clientId).queryParam("redirect_uri", redirectUri).queryParam("response_type", "code")
            .queryParam("scope", "read:hr employees:read payrolls:read").queryParam("state", state).build().toUriString(); }
    public void handleCallback(String code, String state, Long userId) {
        if (code == null || code.isBlank()) throw BusinessException.of("error.payroll.oauthFailed");
        MultiValueMap<String,String> form = new LinkedMultiValueMap<>(); form.add("grant_type","authorization_code"); form.add("code",code); form.add("client_id",clientId); form.add("client_secret",clientSecret); form.add("redirect_uri",redirectUri);
        JsonNode n = restTemplate.postForObject(apiBase + "/oauth/token", new HttpEntity<>(form, headersForm()), JsonNode.class);
        if (n == null || n.path("access_token").asText().isBlank()) throw BusinessException.of("error.payroll.oauthFailed");
        FreeeConnection c = connectionMapper.selectOne(new LambdaQueryWrapper<FreeeConnection>().orderByDesc(FreeeConnection::getId).last("LIMIT 1"));
        if (c == null) c = new FreeeConnection(); c.setAccessTokenEncrypted(encrypt(n.path("access_token").asText())); c.setRefreshTokenEncrypted(encrypt(n.path("refresh_token").asText())); c.setTokenExpiresAt(LocalDateTime.now().plusSeconds(n.path("expires_in").asLong(3600))); c.setConnectedBy(userId); if (c.getId()==null) connectionMapper.insert(c); else connectionMapper.updateById(c);
    }
    public boolean connected() { return connectionMapper.selectCount(new LambdaQueryWrapper<FreeeConnection>()) > 0; }
    public void disconnect() { connectionMapper.delete(new LambdaQueryWrapper<FreeeConnection>()); }
    public List<FreeeEmployeeDto> employees() {
        JsonNode arr = get("/hr/api/v1/employees"); List<FreeeEmployeeDto> out = new ArrayList<>();
        List<FreeeEmployeeLink> links = linkMapper.selectList(new LambdaQueryWrapper<FreeeEmployeeLink>());
        Map<String, FreeeEmployeeLink> linkMap = links.stream().collect(java.util.stream.Collectors.toMap(FreeeEmployeeLink::getFreeeEmployeeId, l -> l, (a,b)->a));
        List<Engineer> engineers = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>());
        Map<Long, String> engineerMap = engineers.stream().collect(java.util.stream.Collectors.toMap(Engineer::getId, Engineer::getFullName, (a,b)->a));
        if (arr != null) for (JsonNode n : arr.path("employees")) { 
            if ("BP".equalsIgnoreCase(n.path("employment_type").asText())) continue; 
            FreeeEmployeeDto d=new FreeeEmployeeDto(); 
            d.setId(n.path("id").asText()); 
            d.setDisplayName(n.path("display_name").asText(n.path("name").asText())); 
            d.setEmploymentType(n.path("employment_type").asText()); 
            FreeeEmployeeLink link = linkMap.get(d.getId());
            if (link != null) {
                d.setLinkedEngineerId(link.getEngineerId());
                d.setLinkedEngineerName(engineerMap.get(link.getEngineerId()));
            }
            out.add(d); 
        } 
        return out;
    }
    public void link(Long engineerId,String employeeId,Long userId) { 
        if (employeeId == null || employeeId.isBlank()) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }
        if (employees().stream().noneMatch(e -> e.getId().equals(employeeId))) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }
        Engineer e=engineerMapper.selectById(engineerId); 
        if(e==null||"BP".equalsIgnoreCase(e.getEmploymentType())) throw BusinessException.of("error.payroll.bpExcluded"); 

        FreeeEmployeeLink conflict = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>().eq(FreeeEmployeeLink::getFreeeEmployeeId, employeeId));
        if (conflict != null && !conflict.getEngineerId().equals(engineerId)) {
            throw BusinessException.of(409, "error.payroll.duplicateEmployeeLink");
        }

        FreeeEmployeeLink old=linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>().eq(FreeeEmployeeLink::getEngineerId,engineerId)); 
        FreeeEmployeeLink x=old==null?new FreeeEmployeeLink():old; 
        x.setEngineerId(engineerId); 
        x.setFreeeEmployeeId(employeeId); 
        x.setConfirmedAt(LocalDateTime.now()); 
        x.setConfirmedBy(com.ses.common.util.SecurityUtils.currentUserId()); 
        try {
            if(old==null)linkMapper.insert(x); else linkMapper.updateById(x); 
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw BusinessException.of(409, "error.payroll.duplicateEmployeeLink");
        }
    }
    public void unlink(Long engineerId){ linkMapper.delete(new LambdaQueryWrapper<FreeeEmployeeLink>().eq(FreeeEmployeeLink::getEngineerId,engineerId)); }
    public List<PayrollStatementDto> statements(int year,int month,String type){ if(year<2000||month<1||month>12) throw BusinessException.of("error.payroll.invalidPeriod"); JsonNode arr=get("/hr/api/v1/payroll-statements?year="+year+"&month="+month+"&type="+type); List<PayrollStatementDto> out=new ArrayList<>(); if(arr!=null) for(JsonNode n:arr.path("statements")){ PayrollStatementDto d=new PayrollStatementDto(); d.setEmployeeId(n.path("employee_id").asText()); d.setYear(year); d.setMonth(month); d.setType(type); d.setGrossAmount(decimal(n,"gross_amount")); d.setDeductions(decimal(n,"deductions")); d.setNetAmount(decimal(n,"net_amount")); out.add(d);} return out; }
    private BigDecimal decimal(JsonNode n,String k){return n.has(k)?n.path(k).decimalValue():BigDecimal.ZERO;}
    private JsonNode get(String path){FreeeConnection c=connectionMapper.selectOne(new LambdaQueryWrapper<FreeeConnection>().orderByDesc(FreeeConnection::getId).last("LIMIT 1")); if(c==null)throw BusinessException.of("error.payroll.notConnected"); if(c.getTokenExpiresAt()!=null&&c.getTokenExpiresAt().isBefore(LocalDateTime.now().plusMinutes(1))) refresh(c); HttpHeaders h=new HttpHeaders(); h.setBearerAuth(decrypt(c.getAccessTokenEncrypted())); h.setAccept(List.of(MediaType.APPLICATION_JSON)); try{return restTemplate.exchange(apiBase+path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getBody();}catch(org.springframework.web.client.HttpClientErrorException.Unauthorized ex){ refresh(c); h.setBearerAuth(decrypt(c.getAccessTokenEncrypted())); try{return restTemplate.exchange(apiBase+path,HttpMethod.GET,new HttpEntity<>(h),JsonNode.class).getBody();}catch(Exception e){throw BusinessException.of(503, "error.payroll.providerUnavailable");} }catch(Exception ex){throw BusinessException.of(503, "error.payroll.providerUnavailable");}}
    private void refresh(FreeeConnection c){ try { MultiValueMap<String,String> form=new LinkedMultiValueMap<>(); form.add("grant_type","refresh_token"); form.add("refresh_token",decrypt(c.getRefreshTokenEncrypted())); form.add("client_id",clientId); form.add("client_secret",clientSecret); JsonNode n=restTemplate.postForObject(apiBase+"/oauth/token",new HttpEntity<>(form,headersForm()),JsonNode.class); if(n==null||n.path("access_token").asText().isBlank()) throw new IllegalStateException(); c.setAccessTokenEncrypted(encrypt(n.path("access_token").asText())); if(n.hasNonNull("refresh_token")) c.setRefreshTokenEncrypted(encrypt(n.path("refresh_token").asText())); c.setTokenExpiresAt(LocalDateTime.now().plusSeconds(n.path("expires_in").asLong(3600))); connectionMapper.updateById(c); } catch(Exception e){ throw BusinessException.of("error.payroll.tokenError"); } }
    private HttpHeaders headersForm(){HttpHeaders h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);return h;}
    private String encrypt(String plain){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(),new GCMParameterSpec(128,iv));return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String decrypt(String val){try{String[] p=val.split(":");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}catch(Exception e){throw BusinessException.of("error.payroll.tokenError");}}
    private SecretKeySpec key(){byte[] b=Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8),32);return new SecretKeySpec(b,"AES");}
}
