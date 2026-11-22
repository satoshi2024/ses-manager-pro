package com.ses.config;

/**
 * 本番の起動時検査のうち、管理者の人数・状態に関する条件1つの判定結果（RB-ADMIN-GUARD、deploy-runbook §5.6）。
 *
 * <p>起動時検査（{@link ProductionSecurityConfigurationValidator}・
 * {@code PrivacyProductionConfigurationValidator}）が自分の検査と同じ照会で作り、管理者の継続性guard
 * （{@code AdministratorContinuityGuard}）が変更の前後で比べる。guardは条件の写しを持たないため、起動時検査の条件を
 * 変えるとguardの判定も同じに変わる。</p>
 *
 * @param condition 条件の種類
 * @param evaluated 起動時検査がこの条件を判定するか（bootstrap中、OIDCのtenant・issuerが未設定など、起動時検査が
 *                  判定しない場合は false）
 * @param satisfied 判定する場合に、起動時検査がこの条件では起動を止めないか（判定しない場合は false）
 * @param count     条件を満たす管理者の人数（判定しない・数えられない場合は0）
 * @param required  起動時検査が要求する人数
 */
public record AdministratorConditionCheck(Condition condition, boolean evaluated, boolean satisfied, int count,
                                          int required) {

    /** 起動時検査の条件の種類。並びは拒否の文言を選ぶ優先順でもある。 */
    public enum Condition {
        /** break-glass username以外でOIDCでログインできる有効な管理者（3名以上）。 */
        OIDC_ADMINISTRATORS,
        /** break-glass管理者（ちょうど2件。有効な管理者・MFA登録済み）。 */
        BREAK_GLASS_ADMINISTRATORS,
        /** privacy-adminに所属しOIDCでログインできる有効な管理者（2名以上）。 */
        PRIVACY_ADMINISTRATORS
    }

    /** 起動時検査がこの条件を判定しない状態。 */
    public static AdministratorConditionCheck notEvaluated(Condition condition, int required) {
        return new AdministratorConditionCheck(condition, false, false, 0, required);
    }

    /** 起動時検査がこの条件を判定した結果。 */
    public static AdministratorConditionCheck evaluated(Condition condition, int count, int required,
                                                        boolean satisfied) {
        return new AdministratorConditionCheck(condition, true, satisfied, Math.max(0, count), required);
    }

    /** 起動時検査がこの条件で起動を止める状態か（判定しない条件は止めない）。 */
    public boolean blocksStartup() {
        return evaluated && !satisfied;
    }
}
