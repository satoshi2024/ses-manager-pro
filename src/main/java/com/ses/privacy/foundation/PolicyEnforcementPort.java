package com.ses.privacy.foundation;

/**
 * NF07 policy の内部判定 port（NF07-NF08-LINK、production-bridge-phased-plan.md §4 の LINK 行・§8）。
 *
 * <p>他機能の実行経路（NF08 の外部送信 gate 等）が、実行の直前に「対象 key の current policy が {@code APPROVED_ACTIVE} で、
 * その ID が自分の承認に記載した ID と一致するか」を確かめるためだけに使う。説明照会（{@link PolicyReadonlyQueryPort}）と
 * 同じ catalog・current source・trust anchor・失効状態を読み、同じ検証を通すが、次の点が違う。
 * <ul>
 *   <li>呼出し元の privacy 権限を要求せず、判定対象の tenant だけを束縛する。</li>
 *   <li>説明 DTO（policy の版・digest・有効期間・説明文）を返さず、可否と固定の理由コードだけを返す。</li>
 * </ul>
 * {@code APPROVED_ACTIVE} は実行の必要条件の1つであって十分条件ではない（呼出し側の承認設定は別に要る）。
 *
 * <p>実装の契約: 呼出しのたびに判定し、結果を cache しない。例外を投げない（判定できなければ拒否を返す）。
 * 公開スイッチ OFF・UNKNOWN・EXPIRED・REVOKED・DRIFT・policy ID の不一致、および呼出し側の承認が current policy の発行より前に
 * 署名されていること（{@link PolicyEnforcementRequest#approvalSignedBefore()}）は全て拒否する。
 */
public interface PolicyEnforcementPort {

    PolicyEnforcementDecision check(PolicyEnforcementRequest request);
}
