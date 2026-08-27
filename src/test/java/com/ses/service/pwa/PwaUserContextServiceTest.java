package com.ses.service.pwa;

import com.ses.service.EngineerAccountLinkService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PwaUserContextServiceTest {

    private final EngineerAccountLinkService linkService = mock(EngineerAccountLinkService.class);
    private final Environment environment = mock(Environment.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneOffset.UTC);
    private final PwaUserContextService service = new PwaUserContextService(linkService, environment, clock);

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
        String userAScope = service.current().userScope();

        authenticateAs(8L);
        when(linkService.findEngineerIdByUserId(8L)).thenReturn(10L);

        PwaUserContextService.CurrentContext userB = service.current(userAScope);

        assertThat(userB.userScope()).isNotEqualTo(userAScope);
        assertThatThrownBy(() -> service.assertCurrent(userAScope))
                .hasMessage("error.pwa.userScopeMismatch");
    }

    @Test
    void scopeLease更新後も同一userならqueue保持を許可する() {
        authenticateAs(7L);
        when(linkService.findEngineerIdByUserId(7L)).thenReturn(9L);
        PwaUserContextService.CurrentContext first = service.current();

        Clock afterLease = Clock.fixed(first.issuedAt().plusSeconds(30L * 24 * 60 * 60 + 1), ZoneOffset.UTC);
        PwaUserContextService rotatedService = new PwaUserContextService(linkService, environment, afterLease);
        ReflectionTestUtils.setField(rotatedService, "contextSecret", "test-pwa-secret");

        PwaUserContextService.ContextResolution rotated = rotatedService.resolve(first.userScope());

        assertThat(rotated.preserveQueue()).isTrue();
        assertThat(rotated.context().userScope()).isNotEqualTo(first.userScope());
        assertThat(rotated.context().userId()).isEqualTo(first.userId());
    }

    private void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), null));
    }
}
