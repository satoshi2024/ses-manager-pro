package com.ses.privacy.foundation;

/**
 * hold 解除 proof の拒否理由（NF07-REL、sf-w2-protocol.md §11.1）。固定 code で、設定値・識別子を含めない。
 */
public enum HoldReleaseRejection {
    /** issuer・approver の claim が欠ける。 */
    CLAIM_MISSING,
    /** claim の役割が位置（issuer / approver）と一致しない。 */
    ROLE_MISMATCH,
    /** trust anchor が登録されていない・registry を読めない。 */
    TRUST_ANCHOR_UNKNOWN,
    /** 署名が正しくない・検証できない。 */
    SIGNATURE_INVALID,
    /** 失効状態が UNKNOWN・失効 provider が無い・照会できない。 */
    REVOCATION_UNKNOWN,
    /** 鍵が失効している。 */
    REVOKED,
    /** claim または trust anchor の期限切れ（append の時点を含む）。 */
    EXPIRED,
    /** claim の発行日時または trust anchor の有効期間の開始がまだ来ていない。 */
    NOT_YET_VALID,
    /** trust anchor の範囲（tenant・役割・対象種別）の外。 */
    SCOPE_MISMATCH,
    /** 要求者・発行者・承認者が分離されていない、または発行者・承認者が hold の開始者と同じ（D-05 の規則で分離を求める場合）。 */
    SEPARATION_OF_DUTIES,
    /** hold の開始者が不明で、D-05 の規則が不明の開始者の hold の解除を拒否する（開始者の値が戻るまで）。 */
    HOLD_STARTER_UNKNOWN,
    /** 束縛の tenant が呼出し元の tenant と一致しない。 */
    TENANT_MISMATCH,
    /** 束縛の subject（partition）が hold と一致しない。 */
    SUBJECT_MISMATCH,
    /** 束縛の hold ID が要求の hold と一致しない。 */
    HOLD_MISMATCH,
    /** 束縛の hold revision が有効な hold と一致しない。 */
    HOLD_REVISION_MISMATCH,
    /** 束縛の journal head（revision・event hash）が現在の head と一致しない。 */
    JOURNAL_HEAD_MISMATCH,
    /** 束縛の command ID が要求と一致しない。 */
    COMMAND_MISMATCH,
    /** 束縛の理由 hash が要求の理由コード・ticket・理由本文の hash と一致しない。 */
    REASON_HASH_MISMATCH,
    /** 束縛の要求者が呼出し元と一致しない。 */
    REQUESTER_MISMATCH,
    /** 束縛の hold の開始者が projection の開始者と一致しない。 */
    HOLD_STARTER_MISMATCH
}
