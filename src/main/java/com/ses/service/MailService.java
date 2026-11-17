package com.ses.service;

import java.util.Map;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.entity.MailDelivery;

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

    default MailDispatchResult sendWithTemplate(Long templateId, Map<String, String> params, String to,
                                                Long invoiceId, Long contactId, Long opportunityId) {
        return sendWithTemplate(templateId, params, to, invoiceId);
    }


    /**
     * 件名・本文を直接指定して非同期送信する（テンプレートを使わない場合）。
     *
     * @return 受付結果。status は常に {@code QUEUED}（送信の結果は deliveryId の配信履歴で確かめる）
     */
    MailDispatchResult send(String to, String subject, String body, Long invoiceId);

    /** CRM接点・商機を紐づけた送信経路。IDは永続化するが、外部送信はtransaction外で行う。 */
    default MailDispatchResult send(String to, String subject, String body, Long invoiceId,
                                    Long contactId, Long opportunityId) {
        return send(to, subject, body, invoiceId);
    }

    default MailDispatchResult send(String to, String subject, String body) {
        return send(to, subject, body, null);
    }

    /**
     * {@link #send} が作成した配信履歴（QUEUED）を送信し、結果（SENT・FAILED・DRY_RUN）で更新する。実装は {@code @Async}。
     *
     * <p>業務コードから直接呼ばない。{@code send} がSpringのproxy経由で非同期実行するための入口で、interfaceに置くのは
     * {@code @EnableAsync} 既定のinterface proxy（JDK proxy）でも呼べるようにするため（RB-PRERUN 2）。</p>
     *
     * @param delivery {@code send} が保存した配信履歴
     * @param tenantId 呼出元の明示tenant（非同期threadへ引数で渡す）
     */
    void executeSend(MailDelivery delivery, String tenantId);
}
