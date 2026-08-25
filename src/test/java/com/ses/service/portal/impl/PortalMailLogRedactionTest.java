package com.ses.service.portal.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.portal.PortalMailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * REV-B2.1-P1-005: Portal 通知失敗ログにメール全文・例外メッセージを出さない。
 */
class PortalMailLogRedactionTest {

    private static final String EMAIL = "alice.secret@partner.example.jp";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

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
    void 通知失敗ログにメール全文と例外メッセージを出さない() {
        PortalOrganizationMapper orgMapper = mock(PortalOrganizationMapper.class);
        PortalUserMapper userMapper = mock(PortalUserMapper.class);
        PortalMailService mail = mock(PortalMailService.class);
        MessageSource messages = mock(MessageSource.class);

        PortalOrganization org = new PortalOrganization();
        org.setId(9L);
        when(orgMapper.selectByCustomerId(1L)).thenReturn(org);

        PortalUser user = new PortalUser();
        user.setEmail(EMAIL);
        user.setStatus("ACTIVE");
        user.setNotifyEmail(1);
        user.setPortalOrgId(9L);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(messages.getMessage(anyString(), any(), any())).thenReturn("subj");

        doThrow(new RuntimeException("smtp fail for " + EMAIL))
                .when(mail).sendNotification(eq(EMAIL), anyString(), anyString(), any());

        PortalNotificationServiceImpl service =
                new PortalNotificationServiceImpl(orgMapper, userMapper, mail, messages);
        service.notifyCustomerOrganization(1L, "TYPE_A", "k.s", "k.b", new Object[]{}, "/x");

        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs).doesNotContain(EMAIL);
        assertThat(logs).doesNotContain("alice.secret");
        assertThat(logs).doesNotContain("smtp fail");
        assertThat(logs).contains("errorType=");
        assertThat(logs).contains("al***@partner.example.jp");
    }
}
