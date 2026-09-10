package com.ses.common.audit;

import com.ses.common.util.SecurityUtils;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 1回の業務実行における監査主体を保持するスレッド境界コンテキスト。
 * scheduler/webhookは入口で明示的に設定し、完了時に必ず元へ戻す。
 */
public final class ExecutionActorContext {

    public static final String MDC_ACTOR_TYPE = "actorType";
    public static final String MDC_ACTOR_SOURCE = "actorSource";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_IDEMPOTENCY_KEY = "idempotencyKey";

    private static final ThreadLocal<ActorAttribution> CURRENT = new ThreadLocal<>();

    private ExecutionActorContext() {
    }

    public static ActorAttribution current() {
        return CURRENT.get();
    }

    /** 明示コンテキストがなければ、人間または安全なsystem主体へ解決する。 */
    public static ActorAttribution resolve() {
        ActorAttribution explicit = CURRENT.get();
        if (explicit != null) {
            return explicit;
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId != null) {
            return ActorAttribution.human(userId, null, null);
        }
        return ActorAttribution.schedulerPoll(null, null);
    }

    public static <T> T runAs(ActorAttribution attribution, Supplier<T> action) {
        if (attribution == null || action == null) {
            throw new IllegalArgumentException("監査主体と処理は必須です");
        }
        ActorAttribution previous = CURRENT.get();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        set(attribution);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            restoreMdc(previousMdc);
        }
    }

    public static void runAs(ActorAttribution attribution, Runnable action) {
        runAs(attribution, () -> {
            action.run();
            return null;
        });
    }

    /** scheduler実行用。継承した人間SecurityContextを認可計算用にも実行主体用にも使わない。 */
    public static <T> T runAsSystem(String correlationId, String idempotencyKey, Supplier<T> action) {
        return runWithClearedSecurityContext(
                () -> runAs(ActorAttribution.schedulerPoll(correlationId, idempotencyKey), action));
    }

    public static void runAsSystem(String correlationId, String idempotencyKey, Runnable action) {
        runAsSystem(correlationId, idempotencyKey, () -> {
            action.run();
            return null;
        });
    }

    /** provider callback用。provider由来の処理を人間認証から切り離す。 */
    public static <T> T runAsProviderCallback(String correlationId, String idempotencyKey,
                                               Supplier<T> action) {
        return runWithClearedSecurityContext(
                () -> runAs(ActorAttribution.providerCallback(correlationId, idempotencyKey), action));
    }

    public static void runAsProviderCallback(String correlationId, String idempotencyKey,
                                              Runnable action) {
        runAsProviderCallback(correlationId, idempotencyKey, () -> {
            action.run();
            return null;
        });
    }

    private static <T> T runWithClearedSecurityContext(Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        SecurityContextHolder.clearContext();
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
            restoreMdc(previousMdc);
        }
    }

    public static void set(ActorAttribution attribution) {
        CURRENT.set(attribution);
        putOrRemove(MDC_ACTOR_TYPE, attribution.actorType().name());
        putOrRemove(MDC_ACTOR_SOURCE, attribution.confirmationSource().name());
        putOrRemove(MDC_CORRELATION_ID, attribution.correlationId());
        putOrRemove(MDC_IDEMPOTENCY_KEY, attribution.idempotencyKey());
    }

    public static void restore(ActorAttribution previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
        // 呼出し側がMDC snapshotを保持していない旧コード向けにはactor項目だけを戻す。
        restoreActorMdc(previous);
    }

    private static void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc == null || previousMdc.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    private static void restoreActorMdc(ActorAttribution previous) {
        if (previous == null) {
            MDC.remove(MDC_ACTOR_TYPE);
            MDC.remove(MDC_ACTOR_SOURCE);
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_IDEMPOTENCY_KEY);
        } else {
            putOrRemove(MDC_ACTOR_TYPE, previous.actorType().name());
            putOrRemove(MDC_ACTOR_SOURCE, previous.confirmationSource().name());
            putOrRemove(MDC_CORRELATION_ID, previous.correlationId());
            putOrRemove(MDC_IDEMPOTENCY_KEY, previous.idempotencyKey());
        }
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
