package com.ses.privacy.foundation;

import java.time.Instant;

/**
 * 内部判定 port（{@link PolicyEnforcementPort}）への要求。
 *
 * <p>束縛するのは判定対象の tenant だけで、呼出し元の利用者・役割は持たない（計画 §8: 一般利用者の送信からも呼ぶため、
 * privacy 管理者の権限を要求しない）。key（module・data element・action・provider）は呼出し側がコードの定数で渡し、
 * 利用者の入力から作らない。{@code expectedPolicyId} は呼出し側の承認（NF08 では {@code ai.external-send-approval.nf07-policy-id}）
 * に記載された policy ID で、current policy の ID と完全一致しなければ拒否する。
 *
 * <p>{@code approvalSignedBefore} は呼出し側の承認が署名された時点の上限（NF08 では署名日の翌日 0 時 JST）。current policy の
 * envelope がこの時点以後に発行されていれば、呼出し側の承認は current policy の発行より前に署名されたもの（policy の再発行で
 * 置き換えられた承認の再利用）として拒否する（W3-03 P3-2 の最小の防護。external-send-approval-gate.md §7.4）。
 *
 * <p>constructor は値を検査しない（不完全な要求は判定が {@link PolicyEnforcementReason#REQUEST_INVALID} で拒否する）。
 */
public record PolicyEnforcementRequest(String tenantId, String moduleCode, String dataElementId, String action,
                                       String providerId, String expectedPolicyId, Instant approvalSignedBefore) {
}
