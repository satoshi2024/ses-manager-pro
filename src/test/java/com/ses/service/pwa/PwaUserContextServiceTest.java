package com.ses.service.pwa;

import com.ses.service.EngineerAccountLinkService;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.entity.EngineerAccountLink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PwaUserContextServiceTest {

    private final EngineerAccountLinkService linkService = mock(EngineerAccountLinkService.class);
    private final EngineerAccountLinkMapper linkMapper = mock(EngineerAccountLinkMapper.class);
    private final Environment environment = mock(Environment.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
    private final PwaUserContextService service = new PwaUserContextService(linkService, linkMapper, environment, clock);

    PwaUserContextServiceTest() {
        ReflectionTestUtils.setField(service, "contextSecret", "test-pwa-secret");
        when(environment.acceptsProfiles("prod")).thenReturn(false);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 同一userの再認証は未失効scopeを再利用しtoken本体へ内部IDを出さない() {
        authenticateAs(7L);
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        stubLink(7L, "tenant-a", 9L, 44L);

        PwaUserContextService.CurrentContext first = service.current();
        String tokenPayload = new String(Base64.getUrlDecoder().decode(first.userScope().split("\\.")[0]));
        PwaUserContextService.CurrentContext reused = service.current(first.userScope());

        assertThat(tokenPayload).doesNotContain("7:9", "userId", "engineerId");
        assertThat(reused.userScope()).isEqualTo(first.userScope());
        assertThat(service.assertCurrent(first.userScope()).issuedAt()).isEqualTo(first.issuedAt());
    }

    @Test
    void user切替では旧scopeを検証できず新scopeになる() {
        authenticateAs(7L);
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        stubLink(7L, "tenant-a", 9L, 44L);
        String userAScope = service.current().userScope();

        authenticateAs(8L);
        when(linkService.findEngineerIdByUserId(8L)).thenReturn(10L);
        stubLink(8L, "tenant-a", 10L, 45L);

        PwaUserContextService.CurrentContext userB = service.current(userAScope);

        assertThat(userB.userScope()).isNotEqualTo(userAScope);
        assertThatThrownBy(() -> service.assertCurrent(userAScope))
                .hasMessage("error.pwa.userScopeMismatch");
    }

    @Test
    void scopeLease更新後も同一userならqueue保持を許可する() {
        authenticateAs(7L);
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        stubLink(7L, "tenant-a", 9L, 44L);
        PwaUserContextService.CurrentContext first = service.current();

        Clock afterLease = Clock.fixed(first.issuedAt().plusSeconds(30L * 24 * 60 * 60 + 1), ZoneOffset.UTC);
        PwaUserContextService rotatedService = new PwaUserContextService(linkService, linkMapper, environment, afterLease);
        ReflectionTestUtils.setField(rotatedService, "contextSecret", "test-pwa-secret");

        PwaUserContextService.ContextResolution rotated = rotatedService.resolve(first.userScope());

        assertThat(rotated.preserveQueue()).isTrue();
        assertThat(rotated.context().userScope()).isNotEqualTo(first.userScope());
        assertThat(rotated.context().userId()).isEqualTo(first.userId());
    }

    @Test
    void restoreScopeはtenant切替と30日境界とlegacyを拒否する() {
        authenticateAs(7L, "tenant-a");
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        stubLink(7L, "tenant-a", 9L, 44L);
        String scope = service.current().userScope();
        assertThat(service.assertRestoreScope(scope, "tenant-a", 7L, 9L, 44L,
                Instant.parse("2026-08-27T00:00:00Z")).userScope()).isEqualTo(scope);
        assertThatThrownBy(() -> service.assertRestoreScope(scope, "tenant-b", 7L, 9L, 44L,
                Instant.parse("2026-08-27T00:00:00Z")))
                .hasMessage("error.pwa.restore.rejected");

        authenticateAs(7L, "tenant-b");
        stubLink(7L, "tenant-b", 9L, 44L);
        var switched = service.resolve(scope);
        assertThat(switched.preserveQueue()).isFalse();
        assertThat(switched.context().userScope()).isNotEqualTo(scope);

        Clock atExpiry = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z")
                .plusSeconds(30L * 24 * 60 * 60), ZoneOffset.UTC);
        PwaUserContextService expiryService = new PwaUserContextService(linkService, linkMapper, environment, atExpiry);
        ReflectionTestUtils.setField(expiryService, "contextSecret", "test-pwa-secret");
        assertThatThrownBy(() -> expiryService.assertRestoreScope(scope, "tenant-a", 7L, 9L, 44L,
                Instant.parse("2026-08-27T00:00:00Z")))
                .hasMessage("error.pwa.restore.rejected");

        authenticateAs(7L, null);
        String legacy = service.current().userScope();
        assertThatThrownBy(() -> service.assertRestoreScope(legacy, "tenant-a", 7L, 9L, 44L,
                Instant.parse("2026-08-27T00:00:00Z")))
                .hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void restoreScope署名はnanosecondClockとmicrosecondLink時刻を完全精度で往復する() {
        authenticateAs(7L, "tenant-a");
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        Instant issuedAt = Instant.parse("2026-08-28T00:00:00.123456789Z");
        Instant linkedAt = Instant.parse("2026-08-27T12:34:56.123456Z");
        stubLinkAt(7L, "tenant-a", 9L, 44L, linkedAt);
        PwaUserContextService precise = new PwaUserContextService(linkService, linkMapper, environment,
                Clock.fixed(issuedAt, ZoneOffset.UTC));
        ReflectionTestUtils.setField(precise, "contextSecret", "test-pwa-secret");

        String scope = precise.current().userScope();
        assertThat(precise.assertCurrent(scope).userScope()).isEqualTo(scope);
        assertThat(precise.assertRestoreScope(scope, "tenant-a", 7L, 9L, 44L, linkedAt).issuedAt())
                .isEqualTo(issuedAt);
        assertThatThrownBy(() -> precise.assertRestoreScope(scope, "tenant-a", 7L, 9L, 44L,
                linkedAt.plusNanos(1))).hasMessage("error.pwa.restore.rejected");
    }

    @Test
    void tenant署名前legacyScopeはqueue保持を引き継がずrestoreできない() {
        authenticateAs(7L, null);
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        String legacy = service.current().userScope();

        authenticateAs(7L, "tenant-a");
        stubLink(7L, "tenant-a", 9L, 44L);
        var resolution = service.resolve(legacy);
        assertThat(resolution.preserveQueue()).isFalse();
        assertThat(resolution.context().userScope()).isNotEqualTo(legacy);
        assertThatThrownBy(() -> service.assertRestoreScope(legacy, "tenant-a", 7L, 9L, 44L,
                Instant.parse("2026-08-27T00:00:00Z")))
                .hasMessage("error.pwa.restore.rejected");
    }

    private void stubLink(Long userId, String tenantId, Long engineerId, Long linkId) {
        stubLinkAt(userId, tenantId, engineerId, linkId, Instant.parse("2026-08-27T00:00:00Z"));
    }

    private void stubLinkAt(Long userId, String tenantId, Long engineerId, Long linkId, Instant linkedAt) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setId(linkId);
        link.setTenantId(tenantId);
        link.setSysUserId(userId);
        link.setEngineerId(engineerId);
        link.setLinkedAt(java.time.LocalDateTime.ofInstant(linkedAt, ZoneOffset.UTC));
        when(linkMapper.selectByUserIdAndTenant(userId, tenantId)).thenReturn(link);
    }

    private void authenticateAs(Long userId) {
        authenticateAs(userId, "tenant-a");
    }

    private void authenticateAs(Long userId, String tenantId) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), null);
        if (tenantId != null) authentication.setDetails(Map.of("tenant_id", tenantId));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
