package com.ses.service.impl;

import com.ses.entity.EmailTemplate;
import com.ses.service.EmailTemplateService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * メール送信サービスの単体テスト（P8 Task5）。
 * SMTP未設定時はドライラン（送信呼び出しなし・例外なし）、
 * 設定済み時はJavaMailSenderへ送信されることを検証する。
 */
class MailServiceImplTest {

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

    @Test
    void send_SMTP未設定ならドライランで送信呼び出しは行われない() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailServiceImpl service = new MailServiceImpl(
                mock(EmailTemplateService.class),
                senderProvider(null),        // JavaMailSender未生成
                noNotification(),
                "",                          // host空
                "noreply@example.com");

        // 例外なく完了すること
        service.send("to@example.com", "件名", "本文");

        verifyNoInteractions(sender);
    }

    @Test
    void send_SMTP設定済みならJavaMailSenderへ送信する() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailServiceImpl service = new MailServiceImpl(
                mock(EmailTemplateService.class),
                senderProvider(sender),
                noNotification(),
                "smtp.example.com",
                "noreply@example.com");

        service.send("to@example.com", "件名", "本文");

        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendWithTemplate_テンプレートの変数を置換して送信する() {
        EmailTemplate template = new EmailTemplate();
        template.setSubjectTemplate("{{engineerName}}のご提案");
        template.setBodyTemplate("{{projectName}}の件です");

        EmailTemplateService templateService = mock(EmailTemplateService.class);
        when(templateService.getById(1L)).thenReturn(template);

        JavaMailSender sender = mock(JavaMailSender.class);
        MailServiceImpl service = new MailServiceImpl(
                templateService, senderProvider(sender), noNotification(),
                "smtp.example.com", "noreply@example.com");

        service.sendWithTemplate(1L, Map.of("engineerName", "山田", "projectName", "金融PJ"), "to@example.com");

        verify(sender).send(argThat((SimpleMailMessage m) ->
                "山田のご提案".equals(m.getSubject())
                        && m.getText() != null && m.getText().contains("金融PJ")));
    }
}
