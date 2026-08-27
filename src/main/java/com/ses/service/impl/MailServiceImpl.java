package com.ses.service.impl;

import com.ses.common.util.TemplateRenderer;
import com.ses.entity.EmailTemplate;
import com.ses.entity.MailDelivery;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.mapper.MailDeliveryMapper;
import com.ses.service.EmailTemplateService;
import com.ses.service.MailService;
import com.ses.service.NotificationService;
import com.ses.service.integration.EmailProviderAdapter;
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
    @Autowired(required = false)
    private EmailProviderAdapter emailProviderAdapter;

    @Autowired
    @org.springframework.context.annotation.Lazy
    private MailServiceImpl self;

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
            throw com.ses.common.exception.BusinessException.of(400, "メール宛先が不正です");
        }
        EmailTemplate template = emailTemplateService.getById(templateId);
        if (template == null) {
            log.warn("メールテンプレートが見つかりません: id={}", templateId);
            throw com.ses.common.exception.BusinessException.of(400, "メールテンプレートが見つかりません: " + templateId);
        }
        String subject = TemplateRenderer.render(template.getSubjectTemplate(), params);
        String body = TemplateRenderer.render(template.getBodyTemplate(), params);
        return send(to, subject, body, invoiceId);
    }

    @Override
    public MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to,
                                               Long invoiceId, Long contactId, Long opportunityId) {
        if (!StringUtils.hasText(to) || !to.contains("@")) {
            throw com.ses.common.exception.BusinessException.of(400, "メール宛先が不正です");
        }
        EmailTemplate template = emailTemplateService.getById(templateId);
        if (template == null) {
            throw com.ses.common.exception.BusinessException.of(400, "メールテンプレートが見つかりません: " + templateId);
        }
        String subject = TemplateRenderer.render(template.getSubjectTemplate(), params);
        String body = TemplateRenderer.render(template.getBodyTemplate(), params);
        return send(to, subject, body, invoiceId, contactId, opportunityId);
    }

    @Override
    public MailDispatchResult send(String to, String subject, String body, Long invoiceId) {
        return send(to, subject, body, invoiceId, null, null);
    }

    @Override
    public MailDispatchResult send(String to, String subject, String body, Long invoiceId,
                                   Long contactId, Long opportunityId) {
        MailDelivery delivery = new MailDelivery();
        delivery.setRecipient(to);
        delivery.setSubject(subject == null ? "" : subject);
        delivery.setBody(body == null ? "" : body);
        delivery.setStatus("QUEUED");
        delivery.setAttemptCount(0);
        delivery.setQueuedAt(java.time.LocalDateTime.now());
        delivery.setInvoiceId(invoiceId);
        delivery.setContactId(contactId);
        delivery.setOpportunityId(opportunityId);
        if (mailDeliveryMapper != null) {
            mailDeliveryMapper.insert(delivery);
        }
        if (self != null) {
            self.executeSend(delivery);
        } else {
            executeSend(delivery); // fallback for tests
        }
        return new MailDispatchResult(delivery.getId(), delivery.getStatus());
    }

    /** 実際の SMTP 呼び出し。send() が作成した履歴を必ず結果で更新する。 */
    @Async
    public void executeSend(MailDelivery delivery) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        // SMTP未設定（host空 or JavaMailSender未生成）はドライラン
        if (!StringUtils.hasText(host) || sender == null) {
            delivery.setStatus("DRY_RUN");
            delivery.setAttemptCount(1);
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            // 本文・件名・宛先アドレス・招待トークン等の機密情報はログに残さない（ACC-SEC-P1-006）。
            // 配信ID・状態・宛先ドメインのみ記録する。
            log.info("【メールドライラン】deliveryId={} recipientDomain={} status=DRY_RUN",
                    delivery.getId(), recipientDomain(delivery.getRecipient()));
            return;
        }
        try {
            delivery.setAttemptCount(1);
            if (emailProviderAdapter != null) {
                emailProviderAdapter.send(delivery.getRecipient(), delivery.getSubject(), delivery.getBody());
            } else {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(delivery.getRecipient());
                message.setSubject(delivery.getSubject());
                message.setText(delivery.getBody());
                sender.send(message);
            }
            delivery.setStatus("SENT");
            delivery.setSentAt(java.time.LocalDateTime.now());
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            // 宛先アドレス・件名・本文はログに残さない（ACC-SEC-P1-006）。
            log.info("メールを送信しました: deliveryId={} recipientDomain={} status=SENT",
                    delivery.getId(), recipientDomain(delivery.getRecipient()));
        } catch (Exception e) {
            delivery.setStatus("FAILED");
            delivery.setFailedAt(java.time.LocalDateTime.now());
            delivery.setErrorMessage(safeErrorMessage(e));
            if (mailDeliveryMapper != null) mailDeliveryMapper.updateById(delivery);
            // 例外メッセージやスタックトレースには本文・トークンが混入し得るため、例外の型のみを記録する。
            // 宛先アドレス・件名・本文は残さない（ACC-SEC-P1-006）。
            log.error("メール送信に失敗しました: deliveryId={} recipientDomain={} status=FAILED errorType={}",
                    delivery.getId(), recipientDomain(delivery.getRecipient()), e.getClass().getName());
            notificationServiceProvider.ifAvailable(ns ->
                    ns.publish("MAIL_FAILED", "メール送信失敗", maskEmail(delivery.getRecipient()) + " 宛のメール送信に失敗しました",
                            null, "MAIL_FAILED:" + delivery.getId() + ":" + System.currentTimeMillis()));
        }
    }

    /** 宛先アドレスのドメイン部分のみを返す（ローカルパートは記録しない）。 */
    private String recipientDomain(String email) {
        if (email == null) return "***";
        int atIdx = email.lastIndexOf("@");
        if (atIdx < 0 || atIdx == email.length() - 1) return "***";
        return email.substring(atIdx + 1);
    }

    /**
     * DB保存用のエラー要約。例外の型名のみを保持し、本文・トークン等が混入し得る
     * 例外メッセージ本体は保存しない（ACC-SEC-P1-006）。
     */
    private String safeErrorMessage(Throwable e) {
        return e == null ? "UNKNOWN" : e.getClass().getName();
    }

    private String maskEmail(String email) {
        return com.ses.common.util.LogRedaction.maskEmail(email);
    }
}

