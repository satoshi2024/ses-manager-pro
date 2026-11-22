package com.ses.config;

import java.util.List;

/**
 * 本番の起動時検査のうち、管理者の人数・状態に関する条件を、起動時検査と同じ分岐・同じ照会で判定する
 * （RB-ADMIN-GUARD、deploy-runbook §5.6）。
 *
 * <p>実装は起動時検査そのもの（{@link ProductionSecurityConfigurationValidator}・
 * {@code PrivacyProductionConfigurationValidator}。どちらも {@code @Profile("prod")}）で、管理者の継続性guard
 * （{@code AdministratorContinuityGuard}）が変更の前後に呼ぶ。DBへは書かず、起動時だけの副作用（bootstrapの再開・
 * 完了markerの書込み）も起こさない。</p>
 */
public interface AdministratorStartupConditions {

    /** 起動時検査と同じ照会で判定した、この起動時検査が受け持つ条件の結果。 */
    List<AdministratorConditionCheck> checkAdministratorConditions();
}
