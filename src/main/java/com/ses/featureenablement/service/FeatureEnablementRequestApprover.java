package com.ses.featureenablement.service;

import com.ses.entity.FeatureEnablementRequest;

/**
 * 申請の種類ごとの承認の処理（拡張点。design §5.3・§5.6・§6.5。review P3-21）。{@code OPEN} は
 * {@link FeatureEnablementService} が持ち、{@code SIGN_OFF_EXTENSION} は署名の service（FE-A06）、{@code SETTING_CHANGE} は
 * 段階 B（FE-B03）が足す。共通の検査（行為者・申請者以外・未決・期限・版・総スイッチ）と世代の lock・読直し・失効の先行の閉鎖を
 * 終えた後に、同じ transaction の中で呼ぶ。申請は先に {@code APPROVED} にしてから対象の行を書く（複合 FK。review P3-N7）。
 */
public interface FeatureEnablementRequestApprover {

    /** 受け持つ申請の種類（{@code t_feature_enablement_request.request_type}）。 */
    String requestType();

    /** 承認する（lock 済みの未決の申請）。 */
    FeatureEnablementApprovalResult approveLocked(FeatureEnablementWriteSupport.WriteContext context,
                                                  FeatureEnablementRequest request, FeatureEnablementActor approver,
                                                  String decisionReason);
}
