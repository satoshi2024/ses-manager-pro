package com.ses.service;

import com.ses.common.audit.ActorAttribution;
import com.ses.entity.ExternalAccountReference;

/** 失効確認のCASと不変イベント/監査ログを同一トランザクションで扱うサービス。 */
public interface ExternalAccountRevokeConfirmationService {

    ExternalAccountReference confirm(Long referenceId, ActorAttribution attribution);
}
