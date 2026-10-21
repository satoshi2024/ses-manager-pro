package com.ses.service.lifecycle.evidence;

/** 将来のライフサイクル提出へ保存する認可fence値。 */
public record LifecycleAuthorizationEpochs(long global, long tenant, long submitterActor) {

    public LifecycleAuthorizationEpochs {
        if (global < 0 || tenant < 0 || submitterActor < 0) {
            throw new IllegalArgumentException("認可epochは0以上で指定してください");
        }
    }

    /** 確認者自身の現行actor epochは別途検証する。 */
    public boolean submitterStillCurrent(LifecycleAuthorizationEpochs current) {
        return current != null
                && global == current.global
                && tenant == current.tenant
                && submitterActor == current.submitterActor;
    }
}
