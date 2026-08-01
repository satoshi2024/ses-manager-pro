package com.ses.service.integration.impl;

import com.ses.service.integration.EmailProviderAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 現行SMTP実装。将来の外部プロバイダは同じ境界を実装する。 */
@Component
public class SmtpEmailProviderAdapter implements EmailProviderAdapter {
    private final ObjectProvider<JavaMailSender> senderProvider;
    private final String from;

    public SmtpEmailProviderAdapter(ObjectProvider<JavaMailSender> senderProvider,
                                    @Value("${app.mail.from:noreply@example.com}") String from) {
        this.senderProvider = senderProvider;
        this.from = from;
    }

    @Override
    public void send(String recipient, String subject, String body) {
        JavaMailSender sender = senderProvider.getIfAvailable();
        if (sender == null) throw new IllegalStateException("メールプロバイダが未設定です");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
