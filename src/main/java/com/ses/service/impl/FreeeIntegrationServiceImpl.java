package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.freee.hr.FreeeBonusStatement;
import com.ses.dto.freee.hr.FreeeHrEmployee;
import com.ses.dto.freee.hr.FreeePayrollItem;
import com.ses.dto.freee.hr.FreeeSalaryStatement;
import com.ses.dto.freee.hr.FreeeStatementPage;
import com.ses.dto.payroll.FreeeConnectionStatusDto;
import com.ses.dto.payroll.FreeeEmployeeDto;
import com.ses.dto.payroll.PayrollEngineerCandidateDto;
import com.ses.dto.payroll.PayrollItemDto;
import com.ses.dto.payroll.PayrollStatementDto;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.entity.Engineer;
import com.ses.entity.FreeeConnection;
import com.ses.entity.FreeeEmployeeLink;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.FreeeConnectionMapper;
import com.ses.mapper.FreeeEmployeeLinkMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.freee.FreeeHrContractAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
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
    /** paginationの最大page数（design §8）。上限到達は空データとして扱わない。 */
    private static final int MAX_PAGES = 1000;
    /** freee APIの1page上限。 */
    private static final int PAGE_SIZE = 100;
    /** 外部待機の総上限（ms）。 */
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** pagination上限（testで縮小可能なseam。上限到達は空データとして扱わない）。 */
    private int maxPages = MAX_PAGES;

    private final FreeeConnectionMapper connectionMapper;
    private final FreeeEmployeeLinkMapper linkMapper;
    private final EngineerMapper engineerMapper;
    private final RestTemplate restTemplate;
    private final org.springframework.context.ApplicationContext applicationContext;

    /** HR responseのtyped parse adapter（productionはSpring bean、unit testは既定インスタンス）。 */
    private FreeeHrContractAdapter hrAdapter = new FreeeHrContractAdapter();

    @Autowired(required = false)
    public void setHrAdapter(FreeeHrContractAdapter hrAdapter) {
        if (hrAdapter != null) {
            this.hrAdapter = hrAdapter;
        }
    }

    /** testで実sleepさせないためのseam（design §11）。 */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private Sleeper sleeper = millis -> Thread.sleep(millis);

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
            // 人事労務API系401の識別子は code フィールド（error は常に access_denied）。
            // token endpointは error フィールド（invalid_grant 等）を使う。code優先で両対応する。
            String code = n.path("code").asText(null);
            if (code != null && !code.isBlank()) {
                return code;
            }
            String error = n.path("error").asText(null);
            return error != null && !error.isBlank() ? error : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<FreeeEmployeeDto> employees() {
        FreeeConnection c = latestActiveRow();
        if (c == null || c.getCompanyId() == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        long companyId = c.getCompanyId();
        List<FreeeHrEmployee> all = fetchAllEmployees(companyId);

        // linkは現在companyのものだけを有効とし、NULL/別companyは要再確認（R04-5/6）
        List<FreeeEmployeeLink> links = linkMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, FreeeEmployeeLink> currentLinks = new HashMap<>();
        Map<String, FreeeEmployeeLink> staleLinks = new HashMap<>();
        for (FreeeEmployeeLink l : links) {
            String key = l.getFreeeEmployeeId();
            if (l.getFreeeCompanyId() != null && l.getFreeeCompanyId() == companyId) {
                currentLinks.put(key, l);
            } else {
                staleLinks.putIfAbsent(key, l);
            }
        }

        List<Engineer> engineers = engineerMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, String> engineerMap = engineers.stream()
                .collect(Collectors.toMap(Engineer::getId, Engineer::getFullName, (a, b) -> a));

        List<FreeeEmployeeDto> out = new ArrayList<>();
        for (FreeeHrEmployee e : all) {
            FreeeEmployeeDto d = new FreeeEmployeeDto();
            d.setId(String.valueOf(e.getId()));
            d.setNum(e.getNum());
            d.setDisplayName(e.getDisplayName());
            d.setEntryDate(e.getEntryDate());
            d.setRetireDate(e.getRetireDate());
            d.setPayrollCalculation(e.getPayrollCalculation());
            // freeeのemployment_typeにはBPは存在しない。BP判定は本システム側（t_engineer）で行う。
            FreeeEmployeeLink link = currentLinks.get(d.getId());
            if (link != null) {
                d.setLinkState("LINKED");
                d.setLinkedEngineerId(link.getEngineerId());
                d.setLinkedEngineerName(engineerMap.get(link.getEngineerId()));
            } else if (staleLinks.containsKey(d.getId())) {
                d.setLinkState("RECONFIRM_REQUIRED");
            } else {
                d.setLinkState("UNLINKED");
            }
            out.add(d);
        }
        return out;
    }

    @Override
    public List<PayrollEngineerCandidateDto> engineerCandidates() {
        // 給与対応付けの候補は非BP・未削除の内部要員だけ（/api/engineers?size=1000依存を廃止）。
        // DB queryの絞り込みに加えてJava側でも防御的に除外する（BP判定は本システム側）。
        List<Engineer> engineers = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getDeletedFlag, 0)
                .ne(Engineer::getEmploymentType, "BP")
                .orderByAsc(Engineer::getFullName));
        return engineers.stream()
                .filter(e -> e.getDeletedFlag() == null || e.getDeletedFlag() == 0)
                .filter(e -> !"BP".equalsIgnoreCase(e.getEmploymentType()))
                .map(e -> {
                    PayrollEngineerCandidateDto d = new PayrollEngineerCandidateDto();
                    d.setId(e.getId());
                    d.setFullName(e.getFullName());
                    d.setEmploymentType(e.getEmploymentType());
                    return d;
                }).collect(Collectors.toList());
    }

    /**
     * 全期間従業員を公式pagination契約（raw配列、limit 100、件数<100で終了）で取得する（design §8.1）。
     */
    private List<FreeeHrEmployee> fetchAllEmployees(long companyId) {
        List<FreeeHrEmployee> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int offset = 0;
        for (int pageNo = 0; pageNo < maxPages; pageNo++) {
            JsonNode raw = hrGet("/api/v1/companies/" + companyId + "/employees",
                    Map.of("with_no_payroll_calculation", "true",
                            "limit", String.valueOf(PAGE_SIZE),
                            "offset", String.valueOf(offset)));
            List<FreeeHrEmployee> page = hrAdapter.companyEmployees(raw);
            for (FreeeHrEmployee e : page) {
                if (!seen.add(String.valueOf(e.getId()))) {
                    throw contractError("従業員IDの反復");
                }
            }
            all.addAll(page);
            if (page.size() < PAGE_SIZE) {
                return all;
            }
            offset += page.size();
        }
        throw contractError("従業員pagination上限到達");
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
        FreeeConnection c = latestActiveRow();
        if (c == null || c.getCompanyId() == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        return "salary".equals(type)
                ? fetchSalaryStatements(c.getCompanyId(), year, month)
                : fetchBonusStatements(c.getCompanyId(), year, month);
    }

    /**
     * 給与一覧を公式root/field・total_count paginationで取得し（design §8.2）、
     * 現在companyの有効link＋非BP・未削除engineerでinner joinする（design §10.2）。
     */
    private List<PayrollStatementDto> fetchSalaryStatements(long companyId, int year, int month) {
        List<FreeeSalaryStatement> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Integer expectedTotal = null;
        int offset = 0;
        for (int pageNo = 0; pageNo < maxPages; pageNo++) {
            JsonNode rawJson = hrGet("/api/v1/salaries/employee_payroll_statements",
                    statementQuery(companyId, year, month, offset));
            FreeeStatementPage<FreeeSalaryStatement> page = hrAdapter.salaryPage(rawJson);
            expectedTotal = validatePageTotal(expectedTotal, page.getTotalCount(), page.getItems().size(),
                    "給与");
            for (FreeeSalaryStatement s : page.getItems()) {
                if (!seen.add(String.valueOf(s.getId()))) {
                    throw contractError("給与明細IDの反復");
                }
                raw.add(s);
                if (raw.size() == expectedTotal) {
                    return mapSalaryStatements(raw, companyId, year, month);
                }
                if (raw.size() > expectedTotal) {
                    throw contractError("給与total_count超過");
                }
            }
            if (raw.size() == expectedTotal) {
                // 0件（total_count=0かつ空配列）が正常な唯一の空ケース
                return mapSalaryStatements(raw, companyId, year, month);
            }
            if (page.getItems().isEmpty()) {
                throw contractError("給与page途中の空配列");
            }
            offset += page.getItems().size();
        }
        throw contractError("給与pagination上限到達");
    }

    /**
     * 賞与一覧を公式root/field・total_count paginationで取得し（design §8.2）、
     * 現在companyの有効link＋非BP・未削除engineerでinner joinする（design §10.2）。
     */
    private List<PayrollStatementDto> fetchBonusStatements(long companyId, int year, int month) {
        List<FreeeBonusStatement> raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Integer expectedTotal = null;
        int offset = 0;
        for (int pageNo = 0; pageNo < maxPages; pageNo++) {
            JsonNode rawJson = hrGet("/api/v1/bonuses/employee_payroll_statements",
                    statementQuery(companyId, year, month, offset));
            FreeeStatementPage<FreeeBonusStatement> page = hrAdapter.bonusPage(rawJson);
            expectedTotal = validatePageTotal(expectedTotal, page.getTotalCount(), page.getItems().size(),
                    "賞与");
            for (FreeeBonusStatement s : page.getItems()) {
                if (!seen.add(String.valueOf(s.getId()))) {
                    throw contractError("賞与明細IDの反復");
                }
                raw.add(s);
                if (raw.size() == expectedTotal) {
                    return mapBonusStatements(raw, companyId, year, month);
                }
                if (raw.size() > expectedTotal) {
                    throw contractError("賞与total_count超過");
                }
            }
            if (raw.size() == expectedTotal) {
                // 0件（total_count=0かつ空配列）が正常な唯一の空ケース
                return mapBonusStatements(raw, companyId, year, month);
            }
            if (page.getItems().isEmpty()) {
                throw contractError("賞与page途中の空配列");
            }
            offset += page.getItems().size();
        }
        throw contractError("賞与pagination上限到達");
    }

    private int validatePageTotal(Integer expectedTotal, int pageTotal, int pageSize, String what) {
        if (expectedTotal == null) {
            if (pageSize > pageTotal) {
                throw contractError(what + "total_count不整合");
            }
            return pageTotal;
        }
        if (pageTotal != expectedTotal) {
            throw contractError(what + "total_count変化");
        }
        return expectedTotal;
    }

    private Map<String, String> statementQuery(long companyId, int year, int month, int offset) {
        return Map.of("company_id", String.valueOf(companyId),
                "year", String.valueOf(year),
                "month", String.valueOf(month),
                "limit", String.valueOf(PAGE_SIZE),
                "offset", String.valueOf(offset));
    }

    /**
     * 現在companyの有効link（freee_company_id=現在company）だけを使い、
     * 非BP・未削除engineerとinner joinする。employee ID→{link, engineer}のmapを返す。
     */
    private Map<String, JoinedEngineer> buildLinkedEngineers(long companyId) {
        List<Engineer> engineers = engineerMapper.selectList(new LambdaQueryWrapper<>());
        Map<Long, Engineer> engineerMap = engineers.stream()
                .filter(e -> e.getDeletedFlag() == null || e.getDeletedFlag() == 0)
                .collect(Collectors.toMap(Engineer::getId, e -> e, (a, b) -> a));

        List<FreeeEmployeeLink> links = linkMapper.selectList(new LambdaQueryWrapper<>());
        Map<String, JoinedEngineer> out = new HashMap<>();
        for (FreeeEmployeeLink l : links) {
            if (l.getFreeeCompanyId() == null || l.getFreeeCompanyId() != companyId) {
                continue; // NULL/別companyのlegacy linkは給与に使わない（R04-6）
            }
            Engineer e = engineerMap.get(l.getEngineerId());
            if (e == null) {
                continue; // 削除済みengineerは除外
            }
            if ("BP".equalsIgnoreCase(e.getEmploymentType())) {
                continue; // BPへ変更済みは除外（R04-5）
            }
            out.put(l.getFreeeEmployeeId(), new JoinedEngineer(e, l));
        }
        return out;
    }

    private record JoinedEngineer(Engineer engineer, FreeeEmployeeLink link) {
    }

    private List<PayrollStatementDto> mapSalaryStatements(List<FreeeSalaryStatement> raw, long companyId,
                                                          int year, int month) {
        Map<String, JoinedEngineer> joined = buildLinkedEngineers(companyId);
        List<PayrollStatementDto> out = new ArrayList<>();
        for (FreeeSalaryStatement s : raw) {
            JoinedEngineer je = joined.get(String.valueOf(s.getEmployeeId()));
            if (je == null) {
                continue; // 未対応freee従業員の金額は返さない（AC08）
            }
            PayrollStatementDto d = new PayrollStatementDto();
            d.setEngineerId(je.engineer().getId());
            d.setEngineerName(je.engineer().getFullName());
            d.setEmployeeId(String.valueOf(s.getEmployeeId()));
            d.setEmployeeNumber(s.getEmployeeNum());
            d.setYear(year);
            d.setMonth(month);
            d.setType("salary");
            d.setPayDate(s.getPayDate());
            d.setFixed(s.getFixed());
            d.setCalculationStatus(s.getCalcStatus());
            d.setGrossAmount(amountOrNull(s.getGrossPaymentAmount()));
            d.setDeductionAmount(amountOrNull(s.getTotalDeductionAmount()));
            d.setNetAmount(amountOrNull(s.getNetPaymentAmount()));
            d.setEmployerShareAmount(amountOrNull(s.getTotalDeductionEmployerShare()));
            List<PayrollItemDto> items = new ArrayList<>();
            for (FreeePayrollItem p : s.getPayments()) {
                items.add(item("PAYMENT", p));
            }
            for (FreeePayrollItem p : s.getDeductions()) {
                items.add(item("DEDUCTION", p));
            }
            for (FreeePayrollItem p : s.getDeductionsEmployerShare()) {
                items.add(item("EMPLOYER_SHARE", p));
            }
            d.setItems(items);
            out.add(d);
        }
        sortStatements(out);
        return out;
    }

    private List<PayrollStatementDto> mapBonusStatements(List<FreeeBonusStatement> raw, long companyId,
                                                         int year, int month) {
        Map<String, JoinedEngineer> joined = buildLinkedEngineers(companyId);
        List<PayrollStatementDto> out = new ArrayList<>();
        for (FreeeBonusStatement s : raw) {
            JoinedEngineer je = joined.get(String.valueOf(s.getEmployeeId()));
            if (je == null) {
                continue; // 未対応freee従業員の金額は返さない（AC08）
            }
            PayrollStatementDto d = new PayrollStatementDto();
            d.setEngineerId(je.engineer().getId());
            d.setEngineerName(je.engineer().getFullName());
            d.setEmployeeId(String.valueOf(s.getEmployeeId()));
            d.setEmployeeNumber(s.getEmployeeNum());
            d.setYear(year);
            d.setMonth(month);
            d.setType("bonus");
            d.setPayDate(s.getPayDate());
            d.setFixed(s.getFixed());
            d.setCalculationStatus(s.getCalcStatus());
            d.setGrossAmount(amountOrNull(s.getGrossPaymentAmount()));
            d.setDeductionAmount(amountOrNull(s.getTotalDeductionAmount()));
            d.setNetAmount(amountOrNull(s.getNetPaymentAmount()));
            List<PayrollItemDto> items = new ArrayList<>();
            for (FreeePayrollItem p : s.getAllowances()) {
                items.add(item("ALLOWANCE", p));
            }
            for (FreeePayrollItem p : s.getDeductions()) {
                items.add(item("DEDUCTION", p));
            }
            d.setItems(items);
            out.add(d);
        }
        sortStatements(out);
        return out;
    }

    private PayrollItemDto item(String category, FreeePayrollItem p) {
        PayrollItemDto d = new PayrollItemDto();
        d.setCategory(category);
        d.setName(p.getName());
        d.setAmount(amountOrNull(p.getAmount()));
        return d;
    }

    /** 返却順は内部要員氏名、employee IDの安定sort（design §10.2）。 */
    private void sortStatements(List<PayrollStatementDto> out) {
        out.sort(java.util.Comparator
                .comparing(PayrollStatementDto::getEngineerName, java.util.Comparator.nullsLast(String::compareTo))
                .thenComparing(PayrollStatementDto::getEmployeeId,
                        java.util.Comparator.nullsLast(String::compareTo)));
    }

    private BigDecimal amountOrNull(String value) {
        // adapterが厳密検証済みなので安全。nullは計算中を意味し0へ変換しない。
        return value == null ? null : new BigDecimal(value.trim());
    }

    private BusinessException contractError(String detail) {
        return BusinessException.of(502, "error.payroll.contractError", detail);
    }

    @Override
    @Transactional
    public void link(Long engineerId, String employeeId, Long userId) {
        FreeeConnection c = latestActiveRow();
        if (c == null || c.getCompanyId() == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        if (!STATUS_CONNECTED.equals(c.getConnectionStatus())) {
            throw BusinessException.of("error.payroll.reauthRequired");
        }
        if (employeeId == null || employeeId.isBlank()) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }
        // 現在companyの従業員一覧に存在すること（生ID手入力の通常操作を廃止）
        boolean exists = fetchAllEmployees(c.getCompanyId()).stream()
                .anyMatch(e -> String.valueOf(e.getId()).equals(employeeId));
        if (!exists) {
            throw BusinessException.of(400, "error.payroll.invalidEmployeeId");
        }

        Engineer e = engineerMapper.selectById(engineerId);
        if (e == null) {
            throw BusinessException.of(400, "error.payroll.invalidEngineer");
        }
        if ("BP".equalsIgnoreCase(e.getEmploymentType())) {
            throw BusinessException.of("error.payroll.bpExcluded");
        }

        // 同一company×同一employeeの既存linkとの競合は409（unique constraintでも防御）
        FreeeEmployeeLink conflict = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getFreeeCompanyId, c.getCompanyId())
                .eq(FreeeEmployeeLink::getFreeeEmployeeId, employeeId));
        if (conflict != null && !conflict.getEngineerId().equals(engineerId)) {
            throw BusinessException.of(409, "error.payroll.duplicateEmployeeLink");
        }

        linkMapper.deleteSoftDeletedConflicts(engineerId, employeeId, c.getCompanyId());

        // engineerの既存row（他company含む）は明示的な再対応付けで現在companyへ更新する（R04-6）
        FreeeEmployeeLink old = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getEngineerId, engineerId));
        FreeeEmployeeLink x = old == null ? new FreeeEmployeeLink() : old;
        x.setEngineerId(engineerId);
        x.setFreeeEmployeeId(employeeId);
        x.setFreeeCompanyId(c.getCompanyId());
        x.setConfirmedAt(LocalDateTime.now());
        // 認証主体はcontrollerから一貫して渡す（SecurityUtilsをservice内で二重取得しない）
        x.setConfirmedBy(userId);

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
        FreeeConnection c = latestActiveRow();
        if (c == null || c.getCompanyId() == null) {
            throw BusinessException.of("error.payroll.notConnected");
        }
        FreeeEmployeeLink x = linkMapper.selectOne(new LambdaQueryWrapper<FreeeEmployeeLink>()
                .eq(FreeeEmployeeLink::getEngineerId, engineerId));
        if (x == null) {
            return;
        }
        // 他companyのlinkは解除対象にしない（IDOR防止）。NULL legacy linkは解除可。
        if (x.getFreeeCompanyId() != null && !x.getFreeeCompanyId().equals(c.getCompanyId())) {
            throw BusinessException.of(400, "error.payroll.companyMismatchLink");
        }
        linkMapper.deleteByEngineerIdHard(engineerId);
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

    /** S15会計APIの金額変換（既存挙動を維持: 欠落は0）。HFP-01の給与経路では使わない。 */
    private BigDecimal decimal(JsonNode n, String k) {
        return n.has(k) ? n.path(k).decimalValue() : BigDecimal.ZERO;
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
     * HR API（HFP-01）用の認証付きGET。base URLはhrApiBase。
     * queryはUriComponentsBuilderで組み立て、company/year/month/limit/offsetを文字列連結しない。
     */
    private JsonNode hrGet(String path, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(path);
        if (queryParams != null) {
            queryParams.forEach(builder::queryParam);
        }
        String uri = builder.build().toUriString();
        return executeWithRetry(hrApiBase, uri, HttpMethod.GET, null, null, null, true);
    }

    /**
     * S11 T072共通基盤: 認証付きGET。401はrefresh 1回＋再試行、429はbackoff、
     * timeout/5xxは503へ変換する。tokenはログへ出力しない。
     */
    @Override
    public JsonNode apiGet(String path) {
        return executeWithRetry(apiBase, path, HttpMethod.GET, null, null, null, false);
    }

    /**
     * S11 T072共通基盤: 認証付きPOST。冪等キーと相関IDをヘッダーへ付与し、
     * 401はrefresh 1回＋再試行、429はbackoff、timeout/5xxは503へ変換する。
     */
    @Override
    public JsonNode apiPost(String path, Object body, String idempotencyKey, String correlationId) {
        return executeWithRetry(apiBase, path, HttpMethod.POST, body, idempotencyKey, correlationId, false);
    }

    private JsonNode executeWithRetry(String baseUrl, String path, HttpMethod method, Object body,
                                      String idempotencyKey, String correlationId) {
        return executeWithRetry(baseUrl, path, method, body, idempotencyKey, correlationId, false);
    }

    /**
     * 認証付きHTTPの共通実行（design §11 error matrix）。
     * <ul>
     *   <li>401はerror codeを分類: expired_access_token等→refresh 1回＋元GET 1回、
     *       re_authorization_required→REAUTH_REQUIRED、user_do_not_have_permission→403（retryなし）、
     *       未知codeはrefresh 1回の後に失敗（無限refreshしない）</li>
     *   <li>429はRetry-After/backoffで最大3回</li>
     *   <li>GETの5xx/timeoutはretryServerErrors時のみ最大2回（S11のapiGet/apiPostは従来どおり即503）</li>
     *   <li>403/404/400等はretryしない</li>
     * </ul>
     */
    private JsonNode executeWithRetry(String baseUrl, String path, HttpMethod method, Object body,
                                      String idempotencyKey, String correlationId,
                                      boolean retryServerErrors) {
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
        int rateLimitAttempts = 0;
        int serverErrorAttempts = 0;
        while (true) {
            try {
                HttpEntity<?> entity = body == null ? new HttpEntity<>(h) : new HttpEntity<>(body, h);
                return restTemplate.exchange(baseUrl + path, method, entity, JsonNode.class).getBody();
            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized ex) {
                String code = errorCode(ex.getResponseBodyAsByteArray());
                if ("re_authorization_required".equals(code)) {
                    markReauthRequired(c);
                    throw BusinessException.of("error.payroll.reauthRequired");
                }
                if ("user_do_not_have_permission".equals(code)) {
                    throw BusinessException.of(403, "error.payroll.permissionDenied");
                }
                if (refreshed) {
                    throw BusinessException.of(401, "error.payroll.tokenError");
                }
                applicationContext.getBean(FreeeIntegrationService.class).refreshForced();
                c = latestActiveRow();
                h = headers(method, decrypt(c.getAccessTokenEncrypted()), idempotencyKey, correlationId);
                refreshed = true;
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests ex) {
                rateLimitAttempts++;
                if (rateLimitAttempts >= 3) {
                    throw BusinessException.of(429, "error.payroll.rateLimited");
                }
                sleepBackoff(rateLimitAttempts, ex);
            } catch (HttpServerErrorException ex) {
                // 5xx
                if (!retryServerErrors || serverErrorAttempts >= 2) {
                    throw BusinessException.of(503, "error.payroll.providerUnavailable");
                }
                serverErrorAttempts++;
                sleepRetry(ex);
            } catch (ResourceAccessException ex) {
                // timeout（saasRestTemplate 5s/15s）
                if (!retryServerErrors || serverErrorAttempts >= 2) {
                    throw BusinessException.of(503, "error.payroll.providerUnavailable");
                }
                serverErrorAttempts++;
                sleepRetry(ex);
            } catch (HttpClientErrorException ex) {
                // 4xxはretryしない（人手修正待ち）
                if (ex.getStatusCode().value() == 403) {
                    throw BusinessException.of(403, "error.payroll.permissionDenied");
                }
                if (ex.getStatusCode().value() == 404) {
                    throw BusinessException.of(404, "error.payroll.notFound");
                }
                throw BusinessException.of(400, "error.payroll.providerRejected");
            } catch (Exception ex) {
                throw BusinessException.of(503, "error.payroll.providerUnavailable");
            }
        }
    }

    /** 429時のexponential backoff + jitter。Retry-After（秒）があればそれを上限内で尊重する。 */
    private void sleepBackoff(int attempt, HttpClientErrorException ex) {
        long base = 500L * (1L << (attempt - 1));
        long jitter = new SecureRandom().nextLong(0, base);
        long wait = Math.min(MAX_BACKOFF_MS, base + jitter);
        String retryAfter = ex.getResponseHeaders() == null ? null
                : ex.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                wait = Math.min(MAX_BACKOFF_MS, Math.max(0, seconds * 1000L));
            } catch (NumberFormatException ignored) {
                // headerがRFC準拠でない場合はbackoffのまま
            }
        }
        log.warn("freee API rate limited (429), retrying in {}ms: status={}", wait,
                ex.getStatusCode().value());
        sleepInterruptibly(wait);
    }

    /** 5xx/timeout retryの待機。上限付き。 */
    private void sleepRetry(Exception ex) {
        long wait = Math.min(MAX_BACKOFF_MS, 1000L);
        log.warn("freee API server error, retrying in {}ms", wait);
        sleepInterruptibly(wait);
    }

    private void sleepInterruptibly(long wait) {
        try {
            sleeper.sleep(wait);
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
