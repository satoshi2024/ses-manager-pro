package com.ses.service.impl;

import com.ses.common.util.TemplateRenderer;
import com.ses.entity.EmailTemplate;
import com.ses.entity.MailDelivery;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.mapper.MailDeliveryMapper;
import com.ses.service.EmailTemplateService;
import com.ses.service.MailService;
import com.ses.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * メール送信サービス実装。
 * SMTP（spring.mail.host）未設定時はドライラン（ログ出力のみ）として動作し、
 * 画面操作を妨げない。送信は@Asyncで非同期実行する。
 */
@Slf4j
@Service
public class MailServiceImpl implements MailService {

    private final EmailTemplateService emailTemplateService;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final ObjectProvider<NotificationService> notificationServiceProvider;
    private final MailDeliveryMapper mailDeliveryMapper;
    private final String host;
    private final String from;

    @Autowired
    public MailServiceImpl(EmailTemplateService emailTemplateService,
                           ObjectProvider<JavaMailSender> mailSenderProvider,
                           ObjectProvider<NotificationService> notificationServiceProvider,
                           MailDeliveryMapper mailDeliveryMapper,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${app.mail.from:noreply@example.com}") String from) {
        this.emailTemplateService = emailTemplateService;
        this.mailSenderProvider = mailSenderProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.mailDeliveryMapper = mailDeliveryMapper;
        this.host = host;
        this.from = from;
    }

    /** 既存の単体テストおよび簡易利用向け互換コンストラクタ。 */
    public MailServiceImpl(EmailTemplateService emailTemplateService,
                           ObjectProvider<JavaMailSender> mailSenderProvider,
                           ObjectProvider<NotificationService> notificationServiceProvider,
                           String host, String from) {
        this(emailTemplateService, mailSenderProvider, notificationServiceProvider, null, host, from);
    }

    @Override
    public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId) {
        if (!StringUtils.hasText(to) || !to.contains("@")) {
            throw new IllegalArgumentException("メール宛先が不正です");
        }
        EmailTemplate template = emailTemplateService.getById(templateId);
        if (template == null) {
            log.warn("メールテンプレートが見つかりません: id={}", templateId);
            throw new IllegalArgumentException("メールテンプレートが見つかりません: " + templateId);
        }
        String subject = TemplateRenderer.render(template.getSubjectTemplate(), params);
        String body = TemplateRenderer.render(template.getBodyTemplate(), params);
        return send(to, subject, body, invoiceId);
    }

    @Override
    public MailDispatchResult send(String to, String subject, String body, Long invoiceId) {
        MailDelivery delivery = new MailDelivery();
        delivery.setRecipient(to);
        delivery.setSubject(subject == null ? "" : subject);
        delivery.setBody(body == null ? "" : body);
        delivery.setStatus("QUEUED");
        delivery.setAttemptCount(0);
        delivery.setQueuedAt(java.time.LocalDateTime.now());
        delivery.setInvoiceId(invoiceId);
        if (mailDeliveryMapper != null) {
            mailDeliveryMapper.insert(delivery);
        }
        executeSend(delivery);
        return new MailDispatchResult(delivery.getId(), delivery.getStatus());
    }

    /** 実際の SMTP 呼び出し。send() が作成した履歴を必ず結果で更新する。 */
    private void executeSend(MailDelivery delivery) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        // SMTP未設定（host空 or JavaMailSender未生成）はドライラン
        if (!StringUtils.hasText(host) || sender == null) {
            delivery.setStatus("DRY_RUN");
            delivery.setAttemptCount(1);
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            log.info("【メールドライラン】from={} to={} subject={}\n{}", from, delivery.getRecipient(), delivery.getSubject(), delivery.getBody());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(delivery.getRecipient());
            message.setSubject(delivery.getSubject());
            message.setText(delivery.getBody());
            delivery.setAttemptCount(1);
            sender.send(message);
            delivery.setStatus("SENT");
            delivery.setSentAt(java.time.LocalDateTime.now());
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            log.info("メールを送信しました: to={} subject={}", delivery.getRecipient(), delivery.getSubject());
        } catch (Exception e) {
            delivery.setStatus("FAILED");
            delivery.setFailedAt(java.time.LocalDateTime.now());
            delivery.setErrorMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            log.error("メール送信に失敗しました: to={} subject={}", delivery.getRecipient(), delivery.getSubject(), e);
            notificationServiceProvider.ifAvailable(ns ->
                    ns.publish("MAIL_FAILED", "メール送信失敗", maskEmail(delivery.getRecipient()) + " 宛のメール送信に失敗しました",
                            null, "MAIL_FAILED:" + delivery.getRecipient() + ":" + System.currentTimeMillis()));
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int atIdx = email.indexOf("@");
        if (atIdx <= 2) return email.charAt(0) + "***" + email.substring(atIdx);
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }
}

