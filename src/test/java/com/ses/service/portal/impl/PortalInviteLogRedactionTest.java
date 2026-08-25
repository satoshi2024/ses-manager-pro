package com.ses.service.portal.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalAccessLogMapper;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalSessionMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.PortalUserPermissionMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.portal.PortalMailService;
import com.ses.service.portal.PortalMfaService;
import com.ses.service.portal.PortalRateLimiter;
import com.ses.service.portal.PortalSessionService;
import com.ses.service.security.DataScopeService;
import com.ses.config.PortalSecurityProperties;
import com.ses.common.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REV-B2.2: Portal 招待失敗・受諾ログにメール全文・token・例外メッセージを出さない。
 */
class PortalInviteLogRedactionTest {

    private static final String EMAIL = "invite.secret@partner.example.jp";
    private static final String RAW_TOKEN = "RAW_INVITE_TOKEN_canary_7e2f";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneId.of("Asia/Tokyo"));

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("com.ses.service.portal.impl");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void 招待メール失敗ログにメール全文と例外メッセージを出さない() {
        PortalOrganizationMapper orgMapper = mock(PortalOrganizationMapper.class);
        PortalUserMapper userMapper = mock(PortalUserMapper.class);
        PortalInvitationMapper invitationMapper = mock(PortalInvitationMapper.class);
        PortalUserPermissionMapper permissionMapper = mock(PortalUserPermissionMapper.class);
        PortalMailService mail = mock(PortalMailService.class);

        PortalOrganization org = new PortalOrganization();
        org.setId(42L);
        org.setStatus("ACTIVE");
        when(orgMapper.selectById(42L)).thenReturn(org);
        when(userMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(invitationMapper.countActiveInvitation(eq(42L), anyString(), any())).thenReturn(0L);
        when(invitationMapper.insert(any(PortalInvitation.class))).thenReturn(1);
        doThrow(new RuntimeException("smtp boom for " + EMAIL + " token=" + RAW_TOKEN))
                .when(mail).sendInvitation(eq(EMAIL), anyString());

        PortalAdminServiceImpl admin = new PortalAdminServiceImpl(
                orgMapper, userMapper, invitationMapper, permissionMapper,
                mock(PortalTermsConsentMapper.class), mock(PortalAccessLogMapper.class),
                mock(PortalSessionMapper.class), mock(PortalSessionService.class),
                mail, mock(SystemConfigService.class), mock(DataScopeService.class), clock);

        admin.createInvitation(42L, EMAIL, "MEMBER", mock(HttpServletRequest.class));

        String logs = joinedLogs();
        assertThat(logs).doesNotContain(EMAIL);
        assertThat(logs).doesNotContain("invite.secret");
        assertThat(logs).doesNotContain(RAW_TOKEN);
        assertThat(logs).doesNotContain("smtp boom");
        assertThat(logs).contains("orgId=42");
        assertThat(logs).contains("errorType=");
        assertThat(logs).contains("in***@partner.example.jp");
    }

    @Test
    void 招待受諾ログにメール全文とtokenを出さない() {
        PortalUserMapper userMapper = mock(PortalUserMapper.class);
        PortalOrganizationMapper orgMapper = mock(PortalOrganizationMapper.class);
        PortalInvitationMapper invitationMapper = mock(PortalInvitationMapper.class);

        PortalInvitation invitation = new PortalInvitation();
        invitation.setId(7L);
        invitation.setPortalOrgId(42L);
        invitation.setEmail(EMAIL);
        invitation.setExpiresAt(LocalDateTime.now(clock).plusHours(1));
        when(invitationMapper.selectByTokenHash(anyString())).thenReturn(invitation);
        when(invitationMapper.consumeIfUnused(eq(7L), any(), isNull())).thenReturn(1);
        when(invitationMapper.update(isNull(), any())).thenReturn(1);

        PortalOrganization org = new PortalOrganization();
        org.setId(42L);
        org.setStatus("ACTIVE");
        when(orgMapper.selectById(42L)).thenReturn(org);
        when(userMapper.selectByEmailIncludingDeleted(EMAIL)).thenReturn(null);
        when(userMapper.insert(any(PortalUser.class))).thenAnswer(inv -> {
            PortalUser u = inv.getArgument(0);
            u.setId(100L);
            return 1;
        });

        PortalAuthServiceImpl auth = new PortalAuthServiceImpl(
                userMapper, orgMapper, invitationMapper, mock(PortalTermsConsentMapper.class),
                mock(PortalMfaService.class), mock(PortalSessionService.class),
                mock(SystemConfigService.class), mock(PortalRateLimiter.class),
                mock(PortalSecurityProperties.class), mock(ClientIpResolver.class), clock);

        PortalAcceptInvitationRequest req = new PortalAcceptInvitationRequest();
        req.setToken(RAW_TOKEN);
        req.setEmail(EMAIL);
        req.setDisplayName("Tester");
        req.setPassword("Password1!");
        auth.acceptInvitation(req, mock(HttpServletRequest.class));

        String logs = joinedLogs();
        assertThat(logs).doesNotContain(EMAIL);
        assertThat(logs).doesNotContain("invite.secret");
        assertThat(logs).doesNotContain(RAW_TOKEN);
        assertThat(logs).contains("orgId=42");
        assertThat(logs).contains("in***@partner.example.jp");
        assertThat(logs).contains("tokenはログへ出さない");
    }

    private String joinedLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
