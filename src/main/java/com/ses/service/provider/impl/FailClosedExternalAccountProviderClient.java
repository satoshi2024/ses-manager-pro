package com.ses.service.provider.impl;

import com.ses.entity.ExternalAccountReference;
import com.ses.service.provider.ExternalAccountProviderClient;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

/** 未配線・未知の外部プロバイダ設定では外部状態を確認できないことを表す。 */
@Service
@Conditional(FailClosedExternalAccountProviderCondition.class)
public class FailClosedExternalAccountProviderClient implements ExternalAccountProviderClient {

    @Override
    public boolean requestRevoke(ExternalAccountReference accountRef) {
        return false;
    }

    @Override
    public RevokeConfirmationStatus checkRevokeConfirmation(ExternalAccountReference accountRef) {
        return RevokeConfirmationStatus.UNKNOWN;
    }

}
