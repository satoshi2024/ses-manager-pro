package com.ses.common.audit;

import com.ses.common.exception.BusinessException;

/**
 * 確認主体と確認チャネルを一体で検証する値オブジェクト。
 * 人間の確認者IDは「確認主体」であり、要求を起票したユーザーIDとは別物である。
 */
public record ActorAttribution(
        ActorType actorType,
        ConfirmationSource confirmationSource,
        Long humanUserId,
        String correlationId,
        String idempotencyKey) {

    public ActorAttribution {
        if (actorType == null || confirmationSource == null) {
            throw new BusinessException(400, "確認主体区分と確認チャネルは必須です。");
        }
        if (humanUserId != null && humanUserId <= 0) {
            throw new BusinessException(400, "人間の確認者IDが不正です。");
        }
        boolean validPair = switch (actorType) {
            case HUMAN -> confirmationSource == ConfirmationSource.MANUAL_API && humanUserId != null;
            case SYSTEM -> confirmationSource == ConfirmationSource.SCHEDULER_POLL && humanUserId == null;
            case PROVIDER -> (confirmationSource == ConfirmationSource.PROVIDER_SYNC
                    || confirmationSource == ConfirmationSource.PROVIDER_CALLBACK) && humanUserId == null;
            case LEGACY_UNRESOLVED -> confirmationSource == ConfirmationSource.LEGACY_UNRESOLVED && humanUserId == null;
        };
        if (!validPair) {
            throw new BusinessException(400, "確認主体と確認チャネルの組み合わせが不正です。");
        }
    }

    public static ActorAttribution human(Long userId, String correlationId, String idempotencyKey) {
        return new ActorAttribution(ActorType.HUMAN, ConfirmationSource.MANUAL_API,
                userId, correlationId, idempotencyKey);
    }

    public static ActorAttribution schedulerPoll(String correlationId, String idempotencyKey) {
        return new ActorAttribution(ActorType.SYSTEM, ConfirmationSource.SCHEDULER_POLL,
                null, correlationId, idempotencyKey);
    }

    public static ActorAttribution providerSync(String correlationId, String idempotencyKey) {
        return new ActorAttribution(ActorType.PROVIDER, ConfirmationSource.PROVIDER_SYNC,
                null, correlationId, idempotencyKey);
    }

    public static ActorAttribution providerCallback(String correlationId, String idempotencyKey) {
        return new ActorAttribution(ActorType.PROVIDER, ConfirmationSource.PROVIDER_CALLBACK,
                null, correlationId, idempotencyKey);
    }

    public static ActorAttribution legacyUnresolved() {
        return new ActorAttribution(ActorType.LEGACY_UNRESOLVED,
                ConfirmationSource.LEGACY_UNRESOLVED, null, null, null);
    }
}
