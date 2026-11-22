package com.ses.backup.quiesce;

/**
 * backup の書込み静止の間に、permit を持たない thread が書込み（必須の監査・uploads の file の変更等）を試みたことを表す
 * （design.md §6.4・§6.12）。
 *
 * <p>{@code BackupWriteGateFilter}（chain 全体）・{@code GlobalExceptionHandler} が 503（{@code X-SES-Error-Code: BACKUP_QUIESCED}）
 * に変える。message には操作の種類だけを入れ、個人データ・path を入れない。
 */
public class BackupWriteQuiescedException extends RuntimeException {

    private final String operation;

    public BackupWriteQuiescedException(String operation) {
        super("backup の書込み静止の間は書込みを受け付けません（" + operation + "）");
        this.operation = operation;
    }

    /** 拒否した操作の種類（log と metric の label の材料。識別子を含めない）。 */
    public String operation() {
        return operation;
    }
}
