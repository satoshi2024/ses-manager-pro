package com.ses.importcenter.metadata;

/**
 * metadata commitのCOMMIT処理自体が失敗し、DB上でcommitされたかを呼出元が判定できないことを表す。
 * transaction開始・コールバック内・rollbackの失敗はCOMMIT未発行で未commitが確定しているため、この例外では包まない。
 * 受け取った側はstage自身の行をreadbackし、見えない場合だけstageを照合用に保全する。
 */
final class ImportCommitOutcomeUnknownException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ImportCommitOutcomeUnknownException(Throwable cause) {
        super("取込metadataのCOMMIT結果を確定できません", cause);
    }
}
