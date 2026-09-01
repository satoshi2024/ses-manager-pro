package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceSlaClockDto;
import com.ses.service.servicedesk.ServiceRequestExportService;
import com.ses.service.servicedesk.ServiceRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceRequestExportServiceImpl implements ServiceRequestExportService {

    private final ServiceRequestService serviceRequestService;

    @Override
    public void exportRequestsToCsv(OutputStream outputStream, String keyword, String status, String priority, String category, Long customerId) {
        try {
            // UTF-8 BOM 出力 (Excel 対応)
            outputStream.write(0xEF);
            outputStream.write(0xBB);
            outputStream.write(0xBF);

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));

            // ヘッダー行
            writer.write("リクエスト番号,顧客名,カテゴリ,優先度,ステータス,件名,初回応答期限,初回応答日時,SLA応答超過,解決期限,解決日時,SLA解決超過,CSATスコア,起票日時");
            writer.newLine();

            // 最大 2000 件取得
            Page<ServiceRequestDto> page = serviceRequestService.searchInternalRequests(1, 2000, keyword, status, priority, category, customerId);
            List<ServiceRequestDto> records = page.getRecords();

            for (ServiceRequestDto r : records) {
                String reqNo = escapeCsv(r.getRequestNo());
                String custName = escapeCsv(r.getCustomerName());
                String cat = escapeCsv(r.getCategory());
                String prio = escapeCsv(r.getPriority());
                String st = escapeCsv(r.getStatus());
                String sub = escapeCsv(r.getSubject());

                ServiceSlaClockDto clock = r.getSlaClock();
                String respDead = clock != null && clock.getResponseDeadline() != null ? clock.getResponseDeadline().toString().replace('T', ' ') : "";
                String firstResp = r.getFirstResponseAt() != null ? r.getFirstResponseAt().toString().replace('T', ' ') : "";
                String respBreached = clock != null && Boolean.TRUE.equals(clock.getResponseBreached()) ? "違反" : "達成";
                String resDead = clock != null && clock.getResolveDeadline() != null ? clock.getResolveDeadline().toString().replace('T', ' ') : "";
                String resolvedAt = r.getResolvedAt() != null ? r.getResolvedAt().toString().replace('T', ' ') : "";
                String resBreached = clock != null && Boolean.TRUE.equals(clock.getResolveBreached()) ? "違反" : "達成";
                String csat = r.getCsatScore() != null ? String.valueOf(r.getCsatScore()) : "";
                String createdAt = r.getCreatedAt() != null ? r.getCreatedAt().toString().replace('T', ' ') : "";

                writer.write(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                        reqNo, custName, cat, prio, st, sub, respDead, firstResp, respBreached, resDead, resolvedAt, resBreached, csat, createdAt));
                writer.newLine();
            }

            writer.flush();
        } catch (Exception e) {
            log.error("CSV エクスポート中にエラーが発生しました", e);
            throw new RuntimeException("CSV エクスポートに失敗しました", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
