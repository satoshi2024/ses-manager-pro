package com.ses.dto.cloudsign;

import java.nio.file.Path;

/**
 * providerからのPDF download結果。memoryにbyte[]を保持せず、size上限付きでstreamした
 * 一時ファイルの参照を返す（design §7.2。binaryを業務DTOへ混在させない）。
 * 呼び出し側（HFP-02-06）がquarantine検証を終えるまでこのtemp fileが寿命。
 */
public record PdfDownload(Path tempPath, long sizeBytes, String contentType) {

    public static PdfDownload of(Path tempPath, long sizeBytes, String contentType) {
        return new PdfDownload(tempPath, sizeBytes, contentType);
    }
}
