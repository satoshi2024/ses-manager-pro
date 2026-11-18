package com.ses.service.accounting;

import com.ses.entity.IntegrationConnection;

/**
 * freee会計接続（m_integration_connection）のOAuth認可を確定するサービス。
 *
 * <p>給与接続（t_freee_connection）と同じOAuth client・redirect URIを使うが、token pairは
 * 認可ごとに別発行して会計接続側へ保存する（freeeのrefresh tokenは使用ごとにrotationするため、
 * 1組のtokenを2つの保存先で共有すると片方のrefreshでもう片方が失効する）。</p>
 */
public interface FreeeAccountingConnectService {

    /**
     * 認可開始前の検証。現在tenantに属するfreee会計接続でなければ404相当のBusinessException。
     */
    IntegrationConnection requireConnectable(Long connectionId);

    /**
     * callback処理: 認可codeを交換し、freee会計の users/me で選択事業所を検証してから
     * 会計接続へtoken・事業所ID・事業所名を保存しCONNECTEDにする。
     * 既に別事業所へ紐付く接続は上書きしない（マッピングの外部IDが事業所固有のため）。
     */
    void completeAuthorization(Long connectionId, String code, Long userId);
}
