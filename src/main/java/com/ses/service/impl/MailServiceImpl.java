package com.ses.service.impl;

import com.ses.common.util.TemplateRenderer;
import com.ses.entity.EmailTemplate;
import com.ses.service.EmailTemplateService;
import com.ses.service.MailService;
import com.ses.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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
    private final String host;
    private final String from;

    public MailServiceImpl(EmailTemplateService emailTemplateService,
                           ObjectProvider<JavaMailSender> mailSenderProvider,
                           ObjectProvider<NotificationService> notificationServiceProvider,
                           @Value("${spring.mail.host:}") String host,
                           @Value("${app.mail.from:noreply@example.com}") String from) {
        this.emailTemplateService = emailTemplateService;
        this.mailSenderProvider = mailSenderProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.host = host;
        this.from = from;
    }

    @Override
    @Async
    public void sendWithTemplate(Long templateId, Map<String, String> params, String to) {
        EmailTemplate template = emailTemplateService.getById(templateId);
        if (template == null) {
            log.warn("メールテンプレートが見つかりません: id={}", templateId);
            return;
        }
        String subject = TemplateRenderer.render(template.getSubjectTemplate(), params);
        String body = TemplateRenderer.render(template.getBodyTemplate(), params);
        send(to, subject, body);
    }

    @Override
    @Async
    public void send(String to, String subject, String body) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        // SMTP未設定（host空 or JavaMailSender未生成）はドライラン
        if (!StringUtils.hasText(host) || sender == null) {
            log.info("【メールドライラン】from={} to={} subject={}\n{}", from, to, subject, body);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            log.info("メールを送信しました: to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("メール送信に失敗しました: to={} subject={}", to, subject, e);
            notificationServiceProvider.ifAvailable(ns ->
                    ns.publish("MAIL_FAILED", "メール送信失敗", to + " 宛のメール送信に失敗しました",
                            null, "MAIL_FAILED:" + to + ":" + System.currentTimeMillis()));
        }
    }
}
