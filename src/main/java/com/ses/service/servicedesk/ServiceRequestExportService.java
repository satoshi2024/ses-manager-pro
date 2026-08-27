package com.ses.service.servicedesk;

import java.io.OutputStream;

public interface ServiceRequestExportService {

    /**
     * サービスデスク問い合わせ一覧を CSV (UTF-8 with BOM) 形式で OutputStream に出力する。
     */
    void exportRequestsToCsv(OutputStream outputStream, String keyword, String status, String priority, String category, Long customerId);
}
