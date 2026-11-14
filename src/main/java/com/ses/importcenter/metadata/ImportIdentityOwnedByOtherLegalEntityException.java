package com.ses.importcenter.metadata;

/**
 * 同一の取込identity（tenant・source・entity・schema・source SHA-256）を持つjobが、現在の利用者とは別の法人に属する。
 *
 * <p>identityの一意制約は法人を含まないため、別法人の利用者が同じ内容をuploadしても新しいjobは作れない。
 * ここで409（jobIdentityConflict）を返すと「同じ内容が別法人に存在する」ことを判定できるoracleになる（B-NF06-5）。
 * 呼出元（{@link ImportCsvIngestionService}）は本例外を、認可contextの変化と同じ汎用403（{@code error.forbidden}）へ
 * 変換し、専用の409を返さない。例外文言・stack traceも応答へ出さない（stack trace未取得）。
 * ただしuploadが成功せず403になること自体が別法人での存在を示すため、存在のoracleは残る
 * （N6-3。Q-15で既知リスクとして受容。解消には法人を含むidentityのDDLが必要）。
 */
final class ImportIdentityOwnedByOtherLegalEntityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ImportIdentityOwnedByOtherLegalEntityException() {
        super("同一取込identityのjobが別法人に存在します", null, false, false);
    }
}
