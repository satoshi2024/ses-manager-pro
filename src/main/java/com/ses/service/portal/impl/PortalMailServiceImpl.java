package com.ses.service.portal.impl;

import com.ses.service.MailService;
import com.ses.service.SystemConfigService;
import com.ses.service.portal.PortalMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * portalメール実装。招待tokenは本文のリンクにのみ含め、ログには出さない（design §2）。
 * base URLはm_system_configのportal.base-domainから組み立てる（field-inventory §6.3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalMailServiceImpl implements PortalMailService {

    private final MailService mailService;
    private final SystemConfigService systemConfigService;

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    @Override
    public void sendInvitation(String to, String token) {
        String base = portalBaseUrl();
        String link = base + "/portal/accept-invitation?token=" + token;
        String body = "SES Manager Pro ポータルへの招待です。\n"
                + "下記リンクから招待を受諾してください（72時間有効・1回限り）。\n"
                + link + "\n"
                + "この招待に心当たりがない場合は、このメールを破棄してください。";
        log.info("portal招待メール: to={} (tokenはログへ出さない)", mask(to));
        mailService.send(to, "【SES Manager Pro】ポータルへの招待", body, null);
    }

    @Override
    public void sendNotification(String to, String subject, String body, String relativeLink) {
        String text = body + "\n" + portalBaseUrl() + relativeLink;
        mailService.send(to, subject, text, null);
    }

    private String portalBaseUrl() {
        String domain = systemConfigService.getString("portal.base-domain", "localhost");
        String scheme = requireHttps ? "https" : "http";
        return scheme + "://" + domain + ":8080";
    }

    private String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        int at = value.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return value.substring(0, 1) + "***" + value.substring(at);
    }
}
