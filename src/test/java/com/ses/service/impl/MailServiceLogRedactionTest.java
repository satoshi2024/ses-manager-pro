package com.ses.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.service.EmailTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B2-5 (ACC-SEC-P1-006 / REV-P1-005): メール送信ログに本文・招待トークン・宛先アドレス全文が
 * 漏れないことを log capture で検証する。
 *
 * <p>DRY_RUN(INFO)・SENT(INFO)・FAILED(ERROR) のいずれの経路でも、
 * 本文/トークン/宛先ローカルパートを出力せず、配信ID・状態・宛先ドメインのみを記録する。
 * 例外経路でも例外メッセージ（本文・トークンを含み得る）を出力しないことを確認する。
 */
class MailServiceLogRedactionTest {

    private static final String RECIPIENT = "invite.token.user@partner.example.jp";
    private static final String RECIPIENT_LOCALPART = "invite.token.user";
    private static final String RECIPIENT_DOMAIN = "partner.example.jp";
    private static final String SUBJECT = "件名SENSITIVE_SUBJECT_S1";
    private static final String BODY = "本文SENSITIVE_BODY_B1 招待トークン=INVITE_TOK_T1";
    private static final String TOKEN = "INVITE_TOK_T1";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("com.ses.service.impl");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> senderProvider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<com.ses.service.NotificationService> noNotification() {
        return mock(ObjectProvider.class);
    }

    private String allLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                sb.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    private void assertNoSensitiveData(String logs) {
        assertFalse(logs.contains(BODY), "本文をログへ出さない");
        assertFalse(logs.contains(TOKEN), "招待トークンをログへ出さない");
        assertFalse(logs.contains(SUBJECT), "件名をログへ出さない");
        assertFalse(logs.contains(RECIPIENT), "宛先アドレス全文をログへ出さない");
        assertFalse(logs.contains(RECIPIENT_LOCALPART), "宛先ローカルパートをログへ出さない");
    }

    @Test
    void ドライランログに本文とトークンと宛先全文を出さない() {
        MailServiceImpl service = new MailServiceImpl(
                mock(EmailTemplateService.class),
                senderProvider(null),   // JavaMailSender 未生成 → DRY_RUN
                noNotification(),
                "",                     // host 空 → DRY_RUN
                "noreply@example.com");

        service.send(RECIPIENT, SUBJECT, BODY);

        String logs = allLogs();
        assertNoSensitiveData(logs);
        assertTrue(logs.contains("DRY_RUN"), "状態(DRY_RUN)は記録してよい");
        assertTrue(logs.contains(RECIPIENT_DOMAIN), "宛先ドメインは記録してよい");
    }

    @Test
    void 送信成功ログに本文とトークンと宛先全文を出さない() {
        JavaMailSender sender = mock(JavaMailSender.class);
        // send は成功（例外なし）
        MailServiceImpl service = new MailServiceImpl(
                mock(EmailTemplateService.class),
                senderProvider(sender),
                noNotification(),
                "smtp.example.com",
                "noreply@example.com");

        service.send(RECIPIENT, SUBJECT, BODY);

        String logs = allLogs();
        assertNoSensitiveData(logs);
        assertTrue(logs.contains("SENT"), "状態(SENT)は記録してよい");
        assertTrue(logs.contains(RECIPIENT_DOMAIN), "宛先ドメインは記録してよい");
    }

    @Test
    void 送信失敗ログに本文とトークンと例外メッセージを出さない() {
        JavaMailSender sender = mock(JavaMailSender.class);
        // 例外メッセージ自体に本文・トークンを仕込み、例外経由の漏洩がないことを検査する。
        doThrow(new RuntimeException(BODY)).when(sender).send(any(org.springframework.mail.SimpleMailMessage.class));

        MailServiceImpl service = new MailServiceImpl(
                mock(EmailTemplateService.class),
                senderProvider(sender),
                noNotification(),
                "smtp.example.com",
                "noreply@example.com");

        service.send(RECIPIENT, SUBJECT, BODY);

        String logs = allLogs();
        assertNoSensitiveData(logs);
        assertTrue(logs.contains("FAILED"), "状態(FAILED)は記録してよい");
        assertTrue(logs.contains(RECIPIENT_DOMAIN), "宛先ドメインは記録してよい");
    }
}
