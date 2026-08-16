package com.ses.service.portal;

/**
 * portal向けメール送信（招待・通知）。既存MailService（t_mail_delivery + DRY_RUN）を利用する。
 * リンクは相対URL（return URL等）を組み立てる際に安全な形だけを使う（design §5）。
 */
public interface PortalMailService {

    /**
     * 招待メールを送信する。本文には招待受諾リンク（相対パス＋token）を含める。
     * tokenはログへ出さない（mailerでmask）。
     */
    void sendInvitation(String to, String token);

    /**
     * portal通知メールを送信する（R4.1）。relativeLinkはportal内の相対パスのみ。
     */
    void sendNotification(String to, String subject, String body, String relativeLink);
}
