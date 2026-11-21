package com.ses.common.util;

import java.util.regex.Pattern;

/**
 * file の download として監査する path（{@code ApiAuditFilter} の download の判定）。
 *
 * <p>{@code ApiAuditFilter}（download の監査 {@code FILE_DOWNLOAD}・{@code FILE_DOWNLOAD_REJECTED}）と、backup の書込み静止の
 * 要求の分類（{@code BackupQuiesceRequestClassifier}。静止中の download は 503。RB-QUIESCE U-10）が同じ判定を使うため、
 * 1 か所にまとめる。判定には復号・正規化済みのアプリ内 path（{@link RequestPaths#pathWithinApplication}）を渡す。
 */
public final class AuditedDownloadPaths {

    private static final Pattern CONTRACT_DOCUMENT_ARTIFACT = Pattern.compile("/api/contract-documents/\\d+/artifacts/\\w+");
    private static final Pattern SALES_ORDER_DOCUMENT = Pattern.compile("/api/sales-orders/\\d+/documents/\\d+/download");
    private static final Pattern SALES_ORDER_ACKNOWLEDGEMENT =
            Pattern.compile("/api/sales-orders/\\d+/acknowledgement-pdf/download");
    private static final Pattern ACCEPTANCE_DOCUMENT = Pattern.compile("/api/acceptances/\\d+/document/download");
    private static final Pattern MY_CHANGE_REQUEST_ATTACHMENT = Pattern.compile("/api/my/change-requests/\\d+/attachment");
    private static final Pattern ENGINEER_CHANGE_REQUEST_ATTACHMENT =
            Pattern.compile("/api/engineer-change-requests/\\d+/attachment");

    private AuditedDownloadPaths() {
    }

    /** GET で呼ぶと file の download として監査する path か。 */
    public static boolean isDownloadUri(String uri) {
        if (uri == null) {
            return false;
        }
        return uri.startsWith("/api/files/")
                || uri.endsWith("/download") || uri.contains("/download/")
                || CONTRACT_DOCUMENT_ARTIFACT.matcher(uri).matches()
                || SALES_ORDER_DOCUMENT.matcher(uri).matches()
                || SALES_ORDER_ACKNOWLEDGEMENT.matcher(uri).matches()
                || ACCEPTANCE_DOCUMENT.matcher(uri).matches()
                || MY_CHANGE_REQUEST_ATTACHMENT.matcher(uri).matches()
                || ENGINEER_CHANGE_REQUEST_ATTACHMENT.matcher(uri).matches();
    }
}
