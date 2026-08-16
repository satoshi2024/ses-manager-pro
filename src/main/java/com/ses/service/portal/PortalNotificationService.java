package com.ses.service.portal;

/**
 * portal向け通知（R4.1: 文書公開・検収・差戻し・支払状態をemail通知）。
 * 宛先はportal組織のACTIVE user全員。同一組織×種別×日の重複送信は抑止する。
 */
public interface PortalNotificationService {

    /**
     * 顧客組織（customer_id）へ通知する。
     */
    void notifyCustomerOrganization(Long customerId, String type, String subjectKey, String bodyKey,
                                    Object[] args, String relativeLink);

    /**
     * BP組織（bp_company_id）へ通知する。
     */
    void notifyBpOrganization(Long bpCompanyId, String type, String subjectKey, String bodyKey,
                              Object[] args, String relativeLink);
}
