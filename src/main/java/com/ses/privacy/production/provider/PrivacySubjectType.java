package com.ses.privacy.production.provider;

/**
 * NF07 の本人（subject）の種別（production-bridge-phased-plan.md §3.2・§3.4.1）。
 *
 * <p>subject 参照は {@code <種別>:<ID>} の opaque 文字列（{@code t_legal_hold.subject_ref} と同じ形）で扱う。
 * 種別をまたいだ自動の名寄せ（例: 候補者→要員、要員→ログインアカウント）は provider では行わない。
 * 本人と subject の対応付けは DSAR の明示 link（{@code t_privacy_request_subject_link}）が行う。
 */
public enum PrivacySubjectType {
    /** 要員（{@code t_engineer.id}）。 */
    ENGINEER,
    /** 候補者（{@code t_candidate.id}）。 */
    CANDIDATE,
    /** ログインアカウント（{@code sys_user.id}）。 */
    SYS_USER,
    /** BP 要員の空き情報（{@code t_bp_availability.id}）。 */
    BP_AVAILABILITY
}
