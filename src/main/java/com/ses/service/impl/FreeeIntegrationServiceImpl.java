package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.entity.Engineer;
import com.ses.entity.FreeeConnection;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * freee連携のOAuth/token/接続管理。
 *
 * <p>HFP-01-003で公式契約へ修正した主な点:</p>
 * <ul>
 *   <li>認可URLはaccounts host＋{@code prompt=select_company}、独自scopeなし（R02-2）</li>
 *   <li>token responseのcompany_idを保存し、{@code /api/v1/users/me}で事業所名と
 *       {@code company_admin}を検証してから接続を確定（R02-4）</li>
 *   <li>接続状態はDISCONNECTED/CONNECTED/REAUTH_REQUIRED/MISCONFIGUREDを区別（R03-1/2）</li>
 *   <li>refreshはrow-lock後再確認・refresh token必須rotation・invalid_grant→REAUTH_REQUIRED（R03-3/4）</li>
 *   <li>解除は公式revoke endpointの成功/既失効を確認してからlocal削除（R03-5）</li>
 * </ul>
 */
@Slf4j
@Service
public class FreeeIntegrationServiceImpl extends ServiceImpl<FreeeConnectionMapper, FreeeConnection> implements FreeeIntegrationService {

    private static final String STATUS_CONNECTED = "CONNECTED";
    private static final String STATUS_REAUTH_REQUIRED = "REAUTH_REQUIRED";
    private static final String OAUTH_AUTHORIZE = "/authorize";
    private static final String OAUTH_TOKEN = "/token";
    private static final String OAUTH_REVOKE = "/revoke";
    private static final String USERS_ME_PATH = "/api/v1/users/me";

    private final FreeeConnectionMapper connectionMapper;
    private final FreeeEmployeeLinkMapper linkMapper;
    private final EngineerMapper engineerMapper;
    private final RestTemplate restTemplate;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Value("${freee.client-id:}")
    private String clientId;

    @Value("${freee.client-secret:}")
    private String clientSecret;

    @Value("${freee.redirect-uri:http://localhost:8080/integrations/freee/callback}")
    private String redirectUri;

    @Value("${freee.api-base-url:https://api.freee.co.jp}")
    private String apiBase;

    @Value("${freee.hr-api-base-url:https://api.freee.co.jp/hr}")
    private String hrApiBase;

    @Value("${freee.oauth-base-url:https://accounts.secure.freee.co.jp/public_api}")
    private String oauthBase;

    @Value("${freee.token-encryption-key:change-me-change-me-change-me-1234}")
    private String encryptionKey;

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    public FreeeIntegrationServiceImpl(
            FreeeConnectionMapper connectionMapper,
            FreeeEmployeeLinkMapper linkMapper,
            EngineerMapper engineerMapper,
            @Qualifier("saasRestTemplate") RestTemplate restTemplate,
            org.springframework.context.ApplicationContext applicationContext) {
        this.connectionMapper = connectionMapper;
        this.linkMapper = linkMapper;
        this.engineerMapper = engineerMapper;
        this.restTemplate = restTemplate;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    void validateConfig() {
        if (activeProfile.contains("prod")) {
            validateOfficialBase("oauth", oauthBase, "accounts.secure.freee.co.jp");
            validateOfficialBase("hr-api", hrApiBase, "api.freee.co.jp");
            validateOfficialBase("api", apiBase, "api.freee.co.jp");
            if (!isBlank(clientId)) {
                if (isBlank(clientSecret) || isBlank(redirectUri) || encryptionKey.startsWith("change-me")) {
                    throw new IllegalStateException(
                            "freee.client-id 設定時は client-secret, redirect-uri(HTTPS), token-encryption-key を一式設定してください");
                }
                URI redirect = URI.create(redirectUri);
                if (!"https".equalsIgnoreCase(redirect.getScheme())) {
                    throw new IllegalStateException("freee.redirect-uri は本番ではHTTPS固定です");
                }
            }
        }
    }

    private void validateOfficialBase(String name, String base, String officialHost) {
        URI uri = URI.create(base);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !officialHost.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException(
                    "freee." + name + "-base-url は本番ではHTTPSかつ公式host(" + officialHost + ")でなければなりません: " + base);
        }
    }

    private boolean configured() {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            return false;
        }
        return !(activeProfile.contains("prod") && encryptionKey.startsWith("change-me"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String authorizationUrl(String state) {
        if (!configured()) {
            throw BusinessException.of("error.payroll.configIncomplete");
        }
        // 公式契約: accounts host + prompt=select_company。公式根拠のないscopeは送らない。
        return UriComponentsBuilder.fromUriString(oauthBase + OAUTH_AUTHORIZE)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state)
                .queryParam("prompt", "select_company")
                .build().toUriString();
    }

    @Override
    @Transactional
    public void handleCallback(String code, String state, Long userId) {
        if (!configured()) {
            throw BusinessException.of("error.payroll.configIncomplete");
        }
        if (isBlank(code)) {
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        // token endpointのPOSTは自動再送しない（認可code二重消費を避ける）。
        JsonNode n;
        try {
            n = tokenEndpointPost(OAUTH_TOKEN, grantForm("authorization_code", code), null);
        } catch (HttpClientErrorException ex) {
            // 認可拒否・code不正はtoken交換失敗として扱う。provider error詳細は出さない。
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        String accessToken = requiredToken(n, "access_token");
        String refreshToken = requiredToken(n, "refresh_token");
        long expiresIn = n.path("expires_in").asLong(0);
        long companyId = n.path("company_id").asLong(0);
        if (expiresIn <= 0 || companyId <= 0) {
            throw BusinessException.of("error.payroll.oauthFailed");
        }

        // DB保存前にusers/meで選択事業所の存在・名称・company_adminを検証する。
        UsersMeCompany company = verifyCompanyAdmin(accessToken, companyId);

        FreeeConnection c = latestActiveRow();
        if (c == null) {
            c = new FreeeConnection();
        }
        c.setCompanyId(companyId);
        c.setCompanyName(company.name());
        c.setAccessTokenEncrypted(encrypt(accessToken));
        c.setRefreshTokenEncrypted(encrypt(refreshToken));
        c.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
        c.setConnectedBy(userId);
        c.setConnectionStatus(STATUS_CONNECTED);
        if (c.getId() == null) {
            connectionMapper.insert(c);
        } else {
            connectionMapper.updateById(c);
        }
    }

    private MultiValueMap<String, String> grantForm(String grantType, String credential) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        if ("authorization_code".equals(grantType)) {
            form.add("code", credential);
        } else {
            form.add("refresh_token", credential);
        }
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        return form;
    }

    private record UsersMeCompany(long id, String name, String role) {
    }

    /**
     * access tokenで{@code /api/v1/users/me}を呼び、選択事業所がcompany_adminであることを確認する。
     * 401/403等のtoken/権限異常や会社不一致はBusinessException（既存接続を上書きしない）。
     */
    private UsersMeCompany verifyCompanyAdmin(String accessToken, long expectedCompanyId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(accessToken);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        JsonNode me;
        try {
            me = restTemplate.exchange(hrApiBase + USERS_ME_PATH, HttpMethod.GET,
                    new HttpEntity<>(h), JsonNode.class).getBody();
        } catch (Exception e) {
            // token error詳細はログ/例外messageへ出さない
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        if (me == null || !me.path("companies").isArray()) {
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        UsersMeCompany found = null;
        for (JsonNode company : me.path("companies")) {
            if (company.path("id").asLong(0) == expectedCompanyId) {
                found = new UsersMeCompany(expectedCompanyId,
                        company.path("name").asText(null), company.path("role").asText(""));
                break;
            }
        }
        if (found == null || found.name() == null) {
            throw BusinessException.of("error.payroll.companyMismatch");
        }
        if (!"company_admin".equals(found.role())) {
            // 給与・賞与一覧は会社管理者権限が必要（R02-4）
            throw BusinessException.of("error.payroll.companyNotAdmin");
        }
        return found;
    }

    private String requiredToken(JsonNode n, String field) {
        if (n == null || n.path(field).asText("").isBlank()) {
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        return n.path(field).asText();
    }

    @Override
    public FreeeConnectionStatusDto connectionStatus() {
        if (!configured()) {
            return statusDto("MISCONFIGURED", null, "payroll.action.misconfigured");
        }
        FreeeConnection c = latestActiveRow();
        if (c == null) {
            return statusDto("DISCONNECTED", null, "payroll.action.connect");
        }
        if (c.getCompanyId() == null || isBlank(c.getAccessTokenEncrypted())
                || isBlank(c.getRefreshTokenEncrypted()) || c.getTokenExpiresAt() == null) {
            return statusDto("MISCONFIGURED", null, "payroll.action.misconfigured");
        }
        if (STATUS_REAUTH_REQUIRED.equals(c.getConnectionStatus())) {
            return statusDto(STATUS_REAUTH_REQUIRED, c.getCompanyName(), "payroll.action.reauth");
        }
        return statusDto(STATUS_CONNECTED, c.getCompanyName(), "payroll.action.connected");
    }

    private FreeeConnectionStatusDto statusDto(String status, String companyName, String actionKey) {
        return FreeeConnectionStatusDto.builder()
                .status(status)
                .connected(STATUS_CONNECTED.equals(status))
                .companyName(companyName)
                .action(actionKey)
                .build();
    }

    @Override
    public boolean connected() {
        return connectionStatus().isConnected();
    }

    private FreeeConnection latestActiveRow() {
        return connectionMapper.selectOne(new LambdaQueryWrapper<FreeeConnection>()
                .orderByDesc(FreeeConnection::getId).last("LIMIT 1"));
    }

    @Override
    public void disconnect() {
        FreeeConnection c = latestActiveRow();
        if (c == null) {
            return;
        }
        // 両方のrevokeが成功/既失効になるまでlocal rowを削除しない（R03-5）。
        revokeToken(decrypt(c.getAccessTokenEncrypted()), "access_token");
        revokeToken(decrypt(c.getRefreshTokenEncrypted()), "refresh_token");
        connectionMapper.deleteById(c.getId());
    }

    /**
     * 公式revoke endpointへ失効要求する。2xxまたは「既に無効」（invalid_grant/invalid_token等）は成功扱い。
     * timeout/5xx/応答不明はBusinessException（local rowは保持され、再実行可能な状態を保つ）。
     */
    private void revokeToken(String token, String tokenType) {
        if (isBlank(token)) {
            throw BusinessException.of("error.payroll.revokeFailed");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        form.add("token_type_hint", tokenType);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        try {
            tokenEndpointPost(OAUTH_REVOKE, form, null);
        } catch (HttpClientErrorException ex) {
            if (isAlreadyInvalid(ex)) {
                return;
            }
            throw BusinessException.of("error.payroll.revokeFailed");
        } catch (BusinessException ex) {
            // timeout/5xx/応答不明は「解除済み」と表示せず、再実行可能な状態を保つ（R03-5）。
            throw BusinessException.of("error.payroll.revokeFailed");
        }
    }

    /** 400系応答が「既に無効」を意味するerror codeのときtrue。 */
    private boolean isAlreadyInvalid(HttpClientErrorException ex) {
        String error = errorCode(ex.getResponseBodyAsByteArray());
        return "invalid_grant".equals(error)
                || "invalid_token".equals(error)
                || "token_expired".equals(error);
    }

    private String errorCode(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            if (n == null) {
                return null;
            }
            String error = n.path("error").asText(null);
            if (error != null && !error.isBlank()) {
                return error;
            }
            // 人事労務API系の401応答は code フィールドを持つ（expired_access_token 等）
            return n.path("code").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<FreeeEmployeeDto> employees() {
        JsonNode arr = get("/hr/api/v1/employees");
        List<FreeeEmployeeDto> out = new ArrayList<>();

        List<FreeeEmployeeLink> links = linkMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, FreeeEmployeeLink> linkMap = links.stream()
                .collect(Collectors.toMap(FreeeEmployeeLink::getFreeeEmployeeId, l -> l, (a, b) -> a));

        List<Engineer> engineers = engineerMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, String> engineerMap = engineers.stream()
                .collect(Collectors.toMap(Engineer::getId, Engineer::getFullName, (a, b) -> a));

        if (arr != null) {
            for (JsonNode n : arr.path("employees")) {
                if ("BP".equalsIgnoreCase(n.path("employment_type").asText())) {
                    continue;
                }
                FreeeEmployeeDto d = new FreeeEmployeeDto();
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
        }
        return out;
    }

    @Override
    public void link(Long engineerId, String employeeId, Long userId) {
        if (employeeId == null || employeeId.isBlank()) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }
        if (employees().stream().noneMatch(e -> e.getId().equals(employeeId))) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }

        Engineer e = engineerMapper.selectById(engineerId);
        if (e == null || "BP".equalsIgnoreCase(e.getEmploymentType())) {
            throw BusinessException.of("error.payroll.bpExcluded");
        }

        FreeeEmployeeLink conflict = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getFreeeEmployeeId, employeeId));
        if (conflict != null && !conflict.getEngineerId().equals(engineerId)) {
            throw BusinessException.of(409, "error.payroll.duplicateEmployeeLink");
        }

        linkMapper.deleteSoftDeletedConflicts(engineerId, employeeId);

        FreeeEmployeeLink old = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getEngineerId, engineerId));
        FreeeEmployeeLink x = old == null ? new FreeeEmployeeLink() : old;
        x.setEngineerId(engineerId);
        x.setFreeeEmployeeId(employeeId);
        x.setConfirmedAt(LocalDateTime.now());
        x.setConfirmedBy(com.ses.common.util.SecurityUtils.currentUserId());

        try {
            if (old == null) {
                linkMapper.insert(x);
            } else {
                linkMapper.updateById(x);
            }
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw BusinessException.of(409, "error.payroll.duplicateEmployeeLink");
        }
    }

    @Override
    public void unlink(Long engineerId) {
        linkMapper.deleteByEngineerIdHard(engineerId);
    }

    @Override
    public List<PayrollStatementDto> statements(int year, int month, String type) {
        if (year < 2000 || month < 1 || month > 12) {
            throw BusinessException.of("error.payroll.invalidPeriod");
        }

        // A7-18: type の検証を追加
        if (!"salary".equals(type) && !"bonus".equals(type)) {
            throw BusinessException.of(400, "error.payroll.invalidType");
        }

        JsonNode arr = get("/hr/api/v1/payroll-statements?year=" + year + "&month=" + month + "&type=" + type);
        List<PayrollStatementDto> out = new ArrayList<>();

        if (arr != null) {
            for (JsonNode n : arr.path("statements")) {
                PayrollStatementDto d = new PayrollStatementDto();
                d.setEmployeeId(n.path("employee_id").asText());
                d.setYear(year);
                d.setMonth(month);
                d.setType(type);
                d.setGrossAmount(decimal(n, "gross_amount"));
                d.setDeductions(decimal(n, "deductions"));
                d.setNetAmount(decimal(n, "net_amount"));
                out.add(d);
            }
        }
        return out;
    }

    private BigDecimal decimal(JsonNode n, String k) {
        return n.has(k) ? n.path(k).decimalValue() : BigDecimal.ZERO;
    }

    @Override
    public List<BankDepositDto> bankDeposits(java.time.LocalDate from, java.time.LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw BusinessException.of(400, "error.reconciliation.invalidPeriod");
        }

        FreeeConnection c = latestActiveRow();
        if (c == null || c.getCompanyId() == null) {
            throw BusinessException.of("error.reconciliation.notConnected");
        }

        // freee会計API: 入金取引(deals, type=income)を取得する。振込名義は明細記載(description)を用いる。
        String path = "/api/1/deals?company_id=" + c.getCompanyId()
                + "&type=income&start_issue_date=" + from + "&end_issue_date=" + to;
        JsonNode arr = get(path);
        List<BankDepositDto> out = new ArrayList<>();

        if (arr != null) {
            for (JsonNode n : arr.path("deals")) {
                BankDepositDto d = new BankDepositDto();
                d.setFreeeDepositId(n.path("id").asText());
                d.setDepositDate(java.time.LocalDate.parse(n.path("issue_date").asText()));
                d.setAmount(decimal(n, "amount"));
                d.setPayerName(n.path("description").asText(""));
                out.add(d);
            }
        }
        return out;
    }

    private JsonNode get(String path) {
        FreeeConnection c = latestActiveRow();
        if (c == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }

        if (c.getTokenExpiresAt() != null && c.getTokenExpiresAt().isBefore(LocalDateTime.now().plusMinutes(1))) {
            applicationContext.getBean(FreeeIntegrationService.class).refresh();
            c = latestActiveRow();
        }

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(decrypt(c.getAccessTokenEncrypted()));
        h.setAccept(List.of(MediaType.APPLICATION_JSON));

        try {
            return restTemplate.exchange(apiBase + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class).getBody();
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized ex) {
            applicationContext.getBean(FreeeIntegrationService.class).refreshForced();
            c = latestActiveRow();
            h.setBearerAuth(decrypt(c.getAccessTokenEncrypted()));
            try {
                return restTemplate.exchange(apiBase + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class).getBody();
            } catch (Exception e) {
                throw BusinessException.of(503, "error.payroll.providerUnavailable");
            }
        } catch (Exception ex) {
            throw BusinessException.of(503, "error.payroll.providerUnavailable");
        }
    }

    /**
     * S11 T072共通基盤: 認証付きGET。401はrefresh 1回＋再試行、429はbackoff、
     * timeout/5xxは503へ変換する。tokenはログへ出力しない。
     */
    @Override
    public JsonNode apiGet(String path) {
        return executeWithRetry(path, HttpMethod.GET, null, null, null);
    }

    /**
     * S11 T072共通基盤: 認証付きPOST。冪等キーと相関IDをヘッダーへ付与し、
     * 401はrefresh 1回＋再試行、429はbackoff、timeout/5xxは503へ変換する。
     */
    @Override
    public JsonNode apiPost(String path, Object body, String idempotencyKey, String correlationId) {
        return executeWithRetry(path, HttpMethod.POST, body, idempotencyKey, correlationId);
    }

    private JsonNode executeWithRetry(String path, HttpMethod method, Object body,
                                      String idempotencyKey, String correlationId) {
        if (path == null || path.isBlank()) {
            throw BusinessException.of(400, "error.payroll.invalidPath");
        }
        FreeeConnection c = latestActiveRow();
        if (c == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        if (c.getTokenExpiresAt() != null && c.getTokenExpiresAt().isBefore(LocalDateTime.now().plusMinutes(1))) {
            applicationContext.getBean(FreeeIntegrationService.class).refresh();
            c = latestActiveRow();
        }
        HttpHeaders h = headers(method, decrypt(c.getAccessTokenEncrypted()), idempotencyKey, correlationId);
        // 401はrefresh 1回に限定する（platform-invariants §7: 無限refreshしない）。
        boolean refreshed = false;
        int attempt = 0;
        while (true) {
            try {
                HttpEntity<?> entity = body == null ? new HttpEntity<>(h) : new HttpEntity<>(body, h);
                return restTemplate.exchange(apiBase + path, method, entity, JsonNode.class).getBody();
            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized ex) {
                if (refreshed) {
                    throw BusinessException.of(401, "error.payroll.tokenError");
                }
                applicationContext.getBean(FreeeIntegrationService.class).refreshForced();
                c = latestActiveRow();
                h = headers(method, decrypt(c.getAccessTokenEncrypted()), idempotencyKey, correlationId);
                refreshed = true;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests ex) {
                attempt++;
                int maxAttempts = 3;
                if (attempt >= maxAttempts) {
                    throw BusinessException.of(429, "error.payroll.rateLimited");
                }
                sleepBackoff(attempt, ex);
            } catch (ResourceAccessException ex) {
                // timeout（saasRestTemplate 5s/15s）はretryしないで503
                throw BusinessException.of(503, "error.payroll.providerUnavailable");
            } catch (HttpClientErrorException ex) {
                // 4xx validationはretryしない（人手修正待ち）
                throw BusinessException.of(400, "error.payroll.providerRejected");
            } catch (Exception ex) {
                throw BusinessException.of(503, "error.payroll.providerUnavailable");
            }
        }
    }

    /** 429時のexponential backoff + jitter。秘密情報はログへ出さない。 */
    private void sleepBackoff(int attempt, HttpClientErrorException ex) {
        long base = 500L * (1L << (attempt - 1));
        long jitter = new SecureRandom().nextLong(0, base);
        log.warn("freee API rate limited (429), retrying in {}ms: status={}", base + jitter,
                ex.getStatusCode().value());
        try {
            Thread.sleep(base + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw BusinessException.of(503, "error.payroll.providerUnavailable");
        }
    }

    private HttpHeaders headers(HttpMethod method, String accessToken,
                                 String idempotencyKey, String correlationId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(accessToken);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method)) {
            h.setContentType(MediaType.APPLICATION_JSON);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            h.set("Idempotency-Key", idempotencyKey);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            h.set("X-Correlation-ID", correlationId);
        }
        return h;
    }

    /**
     * OAuth token/revoke endpointへのform POST。自動retryしない。
     * 2xxはresponse、4xxはHttpClientErrorExceptionをそのまま再throw（呼出側がcodeを判定する）、
     * timeout/5xx/応答不明は503 BusinessException。秘密は例外message/ログへ出さない。
     */
    private JsonNode tokenEndpointPost(String path, MultiValueMap<String, String> form, String correlationId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (correlationId != null && !correlationId.isBlank()) {
            h.set("X-Correlation-ID", correlationId);
        }
        try {
            return restTemplate.postForObject(oauthBase + path, new HttpEntity<>(form, h), JsonNode.class);
        } catch (HttpClientErrorException ex) {
            throw ex;
        } catch (ResourceAccessException ex) {
            throw BusinessException.of(503, "error.payroll.providerUnavailable");
        } catch (Exception ex) {
            throw BusinessException.of("error.payroll.oauthFailed");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refresh() {
        refreshInternal(false);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshForced() {
        refreshInternal(true);
    }

    private void refreshInternal(boolean force) {
        FreeeConnection c = connectionMapper.selectLatestForUpdate();
        if (c == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        // lock取得後にDBを再読込（selectLatestForUpdateの結果が最新）。別threadが更新済みで
        // 有効期限に余裕があれば外部refreshしない。401経路（force）はローカル期限に依らず必ず行う。
        if (!force && c.getTokenExpiresAt() != null
                && c.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(1))) {
            return;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "refresh_token");
            form.add("refresh_token", decrypt(c.getRefreshTokenEncrypted()));
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);

            JsonNode n;
            try {
                n = tokenEndpointPost(OAUTH_TOKEN, form, null);
            } catch (HttpClientErrorException ex) {
                String code = errorCode(ex.getResponseBodyAsByteArray());
                if ("invalid_grant".equals(code) || "re_authorization_required".equals(code)) {
                    // 失効はREAUTH_REQUIREDへ記録し、無限refreshしない（R03-4）。呼出側は再認可messageで停止する。
                    markReauthRequired(c);
                    throw BusinessException.of("error.payroll.reauthRequired");
                }
                throw BusinessException.of("error.payroll.oauthFailed");
            }
            String accessToken = requiredToken(n, "access_token");
            // refresh tokenの新値は必須。欠落時に旧tokenを再利用しない（R03-3）。
            String newRefreshToken = requiredToken(n, "refresh_token");
            long expiresIn = n.path("expires_in").asLong(0);
            if (expiresIn <= 0) {
                throw BusinessException.of("error.payroll.oauthFailed");
            }

            c.setAccessTokenEncrypted(encrypt(accessToken));
            c.setRefreshTokenEncrypted(encrypt(newRefreshToken));
            c.setTokenExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            c.setConnectionStatus(STATUS_CONNECTED);
            connectionMapper.updateById(c);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception e) {
            throw BusinessException.of("error.payroll.tokenError");
        }
    }

    private void markReauthRequired(FreeeConnection c) {
        c.setConnectionStatus(STATUS_REAUTH_REQUIRED);
        connectionMapper.updateById(c);
    }

    private HttpHeaders headersForm() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return h;
    }

    private String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String decrypt(String val) {
        try {
            String[] p = val.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(p[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(p[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw BusinessException.of("error.payroll.tokenError");
        }
    }

    private SecretKeySpec key() {
        byte[] b = Arrays.copyOf(encryptionKey.getBytes(StandardCharsets.UTF_8), 32);
        return new SecretKeySpec(b, "AES");
    }
}
