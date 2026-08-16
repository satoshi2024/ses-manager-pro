package com.ses.service.portal;

import com.ses.portal.PortalLoginUser;

/**
 * portal認可母集団の解決（design §6.2）。
 * 本specはplatform-invariants §2の既定解が適用できない唯一のspecであり、
 * 母集団は portal_org → customer_id / bp_company_id から独立に導出する。既存scope serviceを流用しない。
 * 全portal endpointはこのserviceが返すcustomerId/bpCompanyIdをSQL条件として使う（query boundary）。
 * 取得後checkにしない（R4.3）。
 */
public interface PortalAuthorizationService {

    /**
     * 現在のportal principalを返す（無ければ403）。
     */
    PortalLoginUser requireUser();

    /**
     * 組織種別チェック。
     */
    boolean isCustomerOrg(PortalLoginUser user);
    boolean isBpOrg(PortalLoginUser user);

    /**
     * 組織に紐づく顧客ID（CUSTOMER orgのみ。null可）。
     */
    Long customerId(PortalLoginUser user);

    /**
     * 組織に紐づくBP会社ID（BP orgのみ。null可）。
     */
    Long bpCompanyId(PortalLoginUser user);

    /**
     * 個別付与権限の確認（t_portal_user_permission）。無ければ403。
     */
    void assertPermission(PortalLoginUser user, String permissionKey);

    /**
     * 顧客詳細/更新系のorg scope検証（対象customerIdが自組織と一致しない場合は404秘匿）。
     */
    void assertCustomerScoped(PortalLoginUser user, Long customerId);

    /**
     * BP詳細/更新系のorg scope検証（不一致は404秘匿）。
     */
    void assertBpScoped(PortalLoginUser user, Long bpCompanyId);
}
