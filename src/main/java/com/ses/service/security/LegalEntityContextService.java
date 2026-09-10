package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.service.accounting.AccountingTimezoneResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * 書込み境界で利用する権威法人コンテキスト。
 * クライアントpayloadの法人値は参照せず、現在ユーザーの有効組織からのみ解決する。
 */
@Service
public class LegalEntityContextService {

    private final Clock clock;
    private final AttendanceScopeMapper attendanceScopeMapper;
    private final AccountingTimezoneResolver timezoneResolver;
    private final OidcSecurityProperties oidcSecurityProperties;

    @Autowired
    public LegalEntityContextService(Clock clock, AttendanceScopeMapper attendanceScopeMapper,
                                     AccountingTimezoneResolver timezoneResolver,
                                     OidcSecurityProperties oidcSecurityProperties) {
        this.clock = clock;
        this.attendanceScopeMapper = attendanceScopeMapper;
        this.timezoneResolver = timezoneResolver;
        this.oidcSecurityProperties = oidcSecurityProperties;
    }

    /** 現在のsecurity-bound tenantを返す。AccountingTenantContextHolderの既定値には依存しない。 */
    public String requireTenantId() {
        String tenantId = SecurityUtils.currentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "TENANT_CONTEXT_REQUIRED");
        }
        // ローカル/専用DB構成でも、認証時に束縛したdeployment tenant以外は受け入れない。
        String configuredTenant = oidcSecurityProperties == null ? null : oidcSecurityProperties.getTenantId();
        if (configuredTenant != null && !configuredTenant.isBlank()
                && !configuredTenant.trim().equals(tenantId.trim())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        return tenantId.trim();
    }

    /** 同一リクエストで共有する会計タイムゾーン。呼び出し側は結果をcontextへ保持する。 */
    public ZoneId resolveTenantZone(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "TENANT_CONTEXT_REQUIRED");
        }
        return timezoneResolver.resolve(tenantId);
    }

    /** 現在ユーザーに一意に束縛された法人を返す。曖昧または不明ならfail-closed。 */
    public Long requireCurrentLegalEntityId() {
        String tenantId = requireTenantId();
        ZoneId zone = resolveTenantZone(tenantId);
        return requireCurrentLegalEntityId(clock.instant(), zone);
    }

    /** 通常のwriteで使う業務日付。CopilotExecutionContextと同じtenant timezone/asOf規則で解釈する。 */
    public LocalDate requireCurrentDate() {
        String tenantId = requireTenantId();
        ZoneId zone = resolveTenantZone(tenantId);
        return clock.instant().atZone(zone).toLocalDate();
    }

    /** Factoryと通常writeが同じasOf/zone規則を使うための明示的なoverload。 */
    public Long requireCurrentLegalEntityId(java.time.Instant asOfInstant, ZoneId zone) {
        Long userId = SecurityUtils.currentUserId();
        String role = SecurityUtils.currentRole();
        if (role == null || (userId == null && !"管理者".equals(role))) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        if (asOfInstant == null || zone == null) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        LocalDate asOf = asOfInstant.atZone(zone).toLocalDate();
        List<Long> ids = "管理者".equals(role)
                ? attendanceScopeMapper.selectAllLegalEntityIds()
                : attendanceScopeMapper.selectLegalEntityIdsByUser(userId, asOf);
        if (ids == null || ids.stream().filter(Objects::nonNull).distinct().count() != 1) {
            throw BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED");
        }
        return ids.stream().filter(Objects::nonNull).findFirst().orElseThrow(
                () -> BusinessException.of(403, "LEGAL_ENTITY_CONTEXT_REQUIRED"));
    }

    /** 既存行・関連行・現在ユーザーの法人を同一値へ束縛する。 */
    public void assertSame(Long expected, Long actual) {
        if (expected == null || actual == null || !Objects.equals(expected, actual)) {
            throw BusinessException.of(403, "LEGAL_ENTITY_MISMATCH");
        }
    }

    public void assertCurrent(Long legalEntityId) {
        assertSame(requireCurrentLegalEntityId(), legalEntityId);
    }
}
