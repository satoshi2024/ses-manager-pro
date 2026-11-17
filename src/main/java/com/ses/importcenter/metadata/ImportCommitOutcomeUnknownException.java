package com.ses.importcenter.metadata;

/**
 * metadata commitのCOMMIT処理自体が失敗し、DB上でcommitされたかを呼出元が判定できないことを表す。
 * transaction開始・コールバック内・rollbackの失敗はCOMMIT未発行で未commitが確定しているため、この例外では包まない。
 * 受け取った側はstage自身の行をreadbackし、見えない場合だけstageを照合用に保全する。
 *
 * <p>{@link #commitConnectionId()} はCOMMITを送った接続のserver側ID（MySQLの{@code CONNECTION_ID()}）、
 * {@link #commitServerUuid()} はそのserverの{@code @@server_uuid}で、取得できた場合だけ持つ。照合
 * （{@link ImportStageReconciler}）は、同じserverが再起動せずに動いていて、そのserver threadが文を実行している間は判定しない。
 */
final class ImportCommitOutcomeUnknownException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Long commitConnectionId;
    private final String commitServerUuid;

    ImportCommitOutcomeUnknownException(Throwable cause) {
        this(cause, null);
    }

    ImportCommitOutcomeUnknownException(Throwable cause, ImportCommitConnectionIdentity.CommitConnection connection) {
        super("取込metadataのCOMMIT結果を確定できません", cause);
        this.commitConnectionId = connection == null ? null : connection.connectionId();
        this.commitServerUuid = connection == null ? null : connection.serverUuid();
    }

    /** COMMITを送った接続のserver側ID。取得できなかった（MySQL以外・取得失敗）場合はnull。 */
    Long commitConnectionId() {
        return commitConnectionId;
    }

    /** COMMITを送った接続のserverの{@code @@server_uuid}（小文字）。取得できなかった場合はnull。 */
    String commitServerUuid() {
        return commitServerUuid;
    }
}
