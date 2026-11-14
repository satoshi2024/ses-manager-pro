package com.ses.service.accounting.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.dto.freee.FreeeTokenGrant;
import com.ses.entity.IntegrationConnection;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.FreeeAccountingConnectService;
import com.ses.service.integration.IntegrationConnectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Set;

/**
 * freee会計接続のOAuth認可確定（{@link FreeeAccountingConnectService}）。
 *
 * <p>code交換は給与側と共通の {@link FreeeIntegrationService#exchangeAuthorizationCode(String)} を使い、
 * 事業所検証は会計APIの {@code GET /api/1/users/me?companies=true} で行う（人事労務の利用有無に依存しない）。
 * 外部HTTPはDB transaction外、保存は {@link IntegrationConnectionService#saveTokens} の短TXのみ。</p>
 */
@Slf4j
@Service
public class FreeeAccountingConnectServiceImpl implements FreeeAccountingConnectService {

    private static final String PROVIDER_FREEE = "freee";
    private static final String USERS_ME_PATH = "/api/1/users/me?companies=true";
    /** 会計APIで事業所全体のデータを扱えない権限。 */
    private static final Set<String> INSUFFICIENT_ROLES = Set.of("self_only");

    private final FreeeIntegrationService freeeIntegrationService;
    private final IntegrationConnectionService connectionService;
    private final RestTemplate restTemplate;

    /** FreeeAccountingProviderと同じbase（未指定時は共通の freee.api-base-url）。 */
    @Value("${freee.api.base-url:${freee.api-base-url:https://api.freee.co.jp}}")
    private String apiBaseUrl;

    public FreeeAccountingConnectServiceImpl(FreeeIntegrationService freeeIntegrationService,
                                             IntegrationConnectionService connectionService,
                                             @Qualifier("saasRestTemplate") RestTemplate restTemplate) {
        this.freeeIntegrationService = freeeIntegrationService;
        this.connectionService = connectionService;
        this.restTemplate = restTemplate;
    }

    @Override
    public IntegrationConnection requireConnectable(Long connectionId) {
        if (connectionId == null || connectionId <= 0) {
            throw BusinessException.of(404, "error.accounting.connectionNotFound");
        }
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        IntegrationConnection conn = connectionService.getByIdScoped(connectionId, tenantId, null);
        if (conn == null || !PROVIDER_FREEE.equalsIgnoreCase(conn.getProvider())) {
            throw BusinessException.of(404, "error.accounting.connectionNotFound");
        }
        return conn;
    }

    @Override
    public void completeAuthorization(Long connectionId, String code, Long userId) {
        IntegrationConnection conn = requireConnectable(connectionId);

        FreeeTokenGrant grant = freeeIntegrationService.exchangeAuthorizationCode(code);

        Long boundCompanyId = conn.getExternalCompanyId();
        if (boundCompanyId != null && boundCompanyId > 0 && boundCompanyId != grant.companyId()) {
            log.warn("freee accounting authorization rejected: stage=company_binding connectionId={} reason=different_company",
                    connectionId);
            throw BusinessException.of("error.accounting.freeeCompanyMismatch");
        }

        String companyName = verifyAccountingCompany(grant);

        IntegrationTokensDto tokens = IntegrationTokensDto.builder()
                .accessToken(grant.accessToken())
                .refreshToken(grant.refreshToken())
                .tokenType("bearer")
                .expiresIn(grant.expiresIn())
                .build();
        connectionService.saveTokens(conn.getId(), tokens, grant.companyId(), companyName, userId);
        log.info("freee accounting connection authorized: connectionId={} companyId={}", conn.getId(), grant.companyId());
    }

    /**
     * 会計 users/me で選択事業所が利用者の所属事業所に含まれ、事業所データを扱える権限であることを確認し、
     * 事業所表示名を返す。失敗時はstatusと列挙値だけをログに残す（token・本文は出さない）。
     */
    private String verifyAccountingCompany(FreeeTokenGrant grant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(grant.accessToken());
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        JsonNode me;
        try {
            me = restTemplate.exchange(apiBaseUrl + USERS_ME_PATH, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();
        } catch (HttpClientErrorException e) {
            log.warn("freee accounting authorization failed: stage=accounting_users_me status={}",
                    e.getStatusCode().value());
            if (e.getStatusCode().value() == 403) {
                throw BusinessException.of("error.accounting.freeePermissionDenied");
            }
            throw BusinessException.of("error.payroll.oauthFailed");
        } catch (Exception e) {
            log.warn("freee accounting authorization failed: stage=accounting_users_me type={}",
                    e.getClass().getSimpleName());
            throw BusinessException.of("error.payroll.oauthFailed");
        }

        JsonNode companies = me == null ? null : me.path("user").path("companies");
        if (companies == null || !companies.isArray()) {
            log.warn("freee accounting authorization failed: stage=accounting_users_me reason=companies_missing");
            throw BusinessException.of("error.payroll.oauthFailed");
        }
        for (JsonNode company : companies) {
            if (company.path("id").asLong(0) != grant.companyId()) {
                continue;
            }
            String role = company.path("role").asText("");
            if (INSUFFICIENT_ROLES.contains(role)) {
                log.warn("freee accounting authorization failed: stage=accounting_users_me reason=insufficient_role role={}",
                        role);
                throw BusinessException.of("error.accounting.freeePermissionDenied");
            }
            String name = company.path("display_name").asText("");
            return name.isBlank() ? null : name;
        }
        log.warn("freee accounting authorization failed: stage=accounting_users_me reason=company_not_listed listed_count={}",
                companies.size());
        throw BusinessException.of("error.payroll.companyMismatch");
    }
}
