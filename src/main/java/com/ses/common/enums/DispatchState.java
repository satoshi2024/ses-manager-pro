package com.ses.common.enums;

/**
 * 契約書の技術的な配送工程（HFP-02 design §6.2）。
 * 業務状態({@code status})と分離し、遷移は (id, version, dispatch_state) のCASでのみ行う。
 */
public enum DispatchState {

    /** 未送信（ローカル下書きのまま）。 */
    NONE,
    /** 送信受付済み。workerのclaim待ち。 */
    QUEUED,
    /** provider document作成中。 */
    CREATING,
    /** provider document作成済み。 */
    DOCUMENT_CREATED,
    /** 送信原本PDF upload中。 */
    UPLOADING,
    /** PDF upload済み。 */
    FILE_UPLOADED,
    /** 宛先追加中。 */
    ADDING_PARTICIPANT,
    /** 宛先追加済み。preflight待ち。 */
    READY_TO_SEND,
    /** preflight通過。provider send実行中。 */
    SENDING,
    /** provider送信済み（status=1相当をGET確認済み、または結果不明照合済み）。 */
    SENT,
    /** 締結済（provider status=2を確認。terminal）。 */
    COMPLETED,
    /** 取消・却下（provider status=3を確認。terminal）。 */
    CANCELED,
    /** bounded retry待ち（GET/tokenのみ自動再試行可）。 */
    RETRY_WAIT,
    /** 恒久エラー（4xx等。人手修正待ち）。 */
    FAILED_FINAL,
    /** 結果不明または矛盾。自動mutation再開禁止。人手reconciliationのみで解除。 */
    RECONCILIATION_REQUIRED;
}
