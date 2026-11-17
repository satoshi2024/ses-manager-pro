package com.ses.service.impl;

import com.ses.entity.EmailTemplate;
import com.ses.service.EmailTemplateService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
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

    /**
     * RB-PRERUN 追加 P2-1: send() は受付結果（QUEUED）を返す。送信を同期で終えた場合（Springを使わない構成）でも
     * 返却値は変わらず、送信の結果（DRY_RUN）は配信履歴の複製へ書く。
     */
    @Test
    void send_返却値は受付結果QUEUEDで送信結果は配信履歴へ書く() {
        com.ses.mapper.MailDeliveryMapper mapper = mock(com.ses.mapper.MailDeliveryMapper.class);
        java.util.List<com.ses.entity.MailDelivery> inserted = new java.util.ArrayList<>();
        java.util.List<String> updatedStatuses = new java.util.ArrayList<>();
        java.util.List<com.ses.entity.MailDelivery> updated = new java.util.ArrayList<>();
        when(mapper.insert(any(com.ses.entity.MailDelivery.class))).thenAnswer(invocation -> {
            com.ses.entity.MailDelivery delivery = invocation.getArgument(0);
            delivery.setId(501L);
            inserted.add(delivery);
            return 1;
        });
        when(mapper.updateById(any(com.ses.entity.MailDelivery.class))).thenAnswer(invocation -> {
            com.ses.entity.MailDelivery delivery = invocation.getArgument(0);
            updated.add(delivery);
            updatedStatuses.add(delivery.getStatus());
            return 1;
        });
        MailServiceImpl service = new MailServiceImpl(mock(EmailTemplateService.class), senderProvider(null),
                noNotification(), mapper, "", "noreply@example.com");

        com.ses.dto.mail.MailDispatchResult result = service.send("to@example.com", "件名", "本文", 12L);

        org.junit.jupiter.api.Assertions.assertEquals("QUEUED", result.getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(501L, result.getDeliveryId());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("DRY_RUN"), updatedStatuses);
        org.junit.jupiter.api.Assertions.assertNotSame(inserted.get(0), updated.get(0), "送信側は複製を更新する");
        org.junit.jupiter.api.Assertions.assertEquals("QUEUED", inserted.get(0).getStatus(), "呼出元の配信履歴は書き換えない");
        org.junit.jupiter.api.Assertions.assertEquals(12L, updated.get(0).getInvoiceId());
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
