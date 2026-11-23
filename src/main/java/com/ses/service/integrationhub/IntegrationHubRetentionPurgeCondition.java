package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * retention/nonce purge・read snapshot purge・台帳の metric の生成条件（N5-P2-3、Q-12。feature-enablement-console design §9.1 の 5a）。
 *
 * <p>{@code integration.hub.retention-purge.enabled} があればその値で決める。DB の源（prod）では起動の前の読取り
 * （{@code IntegrationHubEnablementBootResolver}）が、NF05 が一度でも開いたか保存期間の対象の表に行があれば true を書く（読めない時も
 * true。review P2-5・P3-N3）。NF05 が閉じた後の起動でも purge を作り、保存期間を過ぎた個人データを消し続ける（purge の経路は従来どおり
 * {@code PrivacyHoldPathGuard} の D7 を通り、purge の service は普通の bean で行が無ければ何もしない）。</p>
 *
 * <p>無ければ（property の源の dev・test）従来どおり public-api（inbound）または external-transport（outbound）のどちらかが明示的に
 * true なら生成する。outboundだけを開けた場合もdelivery payload（30/90日）とreplay監査（1年）をpurgeする。値の欠落・不正は生成しない
 * 側へ倒す（起動validatorが欠落自体を拒否する）。</p>
 */
public final class IntegrationHubRetentionPurgeCondition implements Condition {
    static final String PUBLIC_API_ENABLED = "integration.hub.public-api.enabled";
    static final String EXTERNAL_TRANSPORT_ENABLED = "integration.hub.external-transport.enabled";
    static final String RETENTION_PURGE_ENABLED = "integration.hub.retention-purge.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
        var explicit = binder.bind(RETENTION_PURGE_ENABLED, Boolean.class);
        if (explicit.isBound()) {
            return explicit.get();
        }
        return binder.bind(PUBLIC_API_ENABLED, Boolean.class).orElse(false)
                || binder.bind(EXTERNAL_TRANSPORT_ENABLED, Boolean.class).orElse(false);
    }
}
