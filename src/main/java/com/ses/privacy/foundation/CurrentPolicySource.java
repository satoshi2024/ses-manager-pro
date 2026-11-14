package com.ses.privacy.foundation;

/**
 * authoritative current policy source の読み取り専用 port（policy-source-catalog-wiring-plan.md §2）。
 *
 * <p>当該 binding の current pointer と署名 envelope・revision・digest を1回の一貫した読取で返す。
 * 履歴から「最新そうなもの」を選ばず、revision を数値比較せず、旧版へ fallback しない。
 * 実装は例外を投げてもよい（呼び出し側が {@code POLICY_UNKNOWN} に閉じる）が、書き込みは行わない。
 */
public interface CurrentPolicySource {
    CurrentPolicySnapshot readCurrent(PolicyLookupKey key, ProductionCatalogBinding binding);
}
