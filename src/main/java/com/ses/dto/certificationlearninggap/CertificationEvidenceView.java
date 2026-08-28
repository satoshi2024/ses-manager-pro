package com.ses.dto.certificationlearninggap;

/** 本人ポータルへ返す証憑版metadata。ダウンロード用の内部storage値は含めない。 */
public record CertificationEvidenceView(
        Long documentId,
        Long documentVersionId,
        Integer versionNo,
        String originalName,
        String sha256,
        String scanStatus) {
}
