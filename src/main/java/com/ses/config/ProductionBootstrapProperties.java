package com.ses.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本番初回起動の期限付きbootstrap mode設定（配備決定: bootstrap方式A）。
 *
 * <p>{@code enabled=true} と期限 {@code expires-at}（offset付きISO-8601、例 {@code 2026-11-14T18:00:00+09:00}）を
 * 同時に指定した場合だけ有効になる。期限の未設定・期限切れ・現在から24時間を超える期限は起動を拒否する
 * （{@link ProductionBootstrapMode}）。prod以外のprofileでは設定しても無視する。
 * 手順は {@code .kiro/specs/production-launch/deploy-runbook.md} §5。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.security.bootstrap")
public class ProductionBootstrapProperties {

    /** 既定はOFF。初回配備の作業中だけ明示的に有効化する。 */
    private boolean enabled = false;

    /** bootstrap modeの期限（offset付きISO-8601）。期限を過ぎるとbootstrap loginとbootstrap sessionを拒否する。 */
    private String expiresAt;
}
