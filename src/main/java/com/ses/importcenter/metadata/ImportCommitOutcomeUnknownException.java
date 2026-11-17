package com.ses.importcenter.metadata;

/**
 * metadata commitのCOMMIT処理自体が失敗し、DB上でcommitされたかを呼出元が判定できないことを表す。
 * transaction開始・コールバック内・rollbackの失敗はCOMMIT未発行で未commitが確定しているため、この例外では包まない。
 * 受け取った側はstage自身の行をreadbackし、見えない場合だけstageを照合用に保全する。
 *
 * <p>{@link #commitConnectionId()} はCOMMITを送った接続のserver側ID（MySQLの{@code CONNECTION_ID()}）で、取得できた場合だけ
 * 持つ。照合（{@link ImportStageReconciler}）は、そのserver threadが文を実行している間は結果が確定していないとして判定しない。
 */
final class ImportCommitOutcomeUnknownException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Long commitConnectionId;

    ImportCommitOutcomeUnknownException(Throwable cause) {
        this(cause, null);
    }

    ImportCommitOutcomeUnknownException(Throwable cause, Long commitConnectionId) {
        super("取込metadataのCOMMIT結果を確定できません", cause);
        this.commitConnectionId = commitConnectionId;
    }

    /** COMMITを送った接続のserver側ID。取得できなかった（MySQL以外・取得失敗）場合はnull。 */
    Long commitConnectionId() {
        return commitConnectionId;
    }
}
