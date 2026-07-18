package com.ses.service;

import java.util.Map;
import com.ses.dto.mail.MailDispatchResult;

/**
 * メール送信サービス。
 */
public interface MailService {

    /**
     * テンプレートIDと変数から件名・本文を組み立てて非同期送信する。
     * SMTP未設定時はドライラン（ログ出力のみ）として動作する。
     *
     * @param templateId m_email_template のID
     * @param params     {{変数}} 置換用のマップ
     * @param to         宛先メールアドレス
     */
    MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to, Long invoiceId);

    default MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to) {
        return sendWithTemplate(templateId, params, to, null);
    }

    /**
     * 件名・本文を直接指定して非同期送信する（テンプレートを使わない場合）。
     */
    MailDispatchResult send(String to, String subject, String body, Long invoiceId);

    default MailDispatchResult send(String to, String subject, String body) {
        return send(to, subject, body, null);
    }
}
