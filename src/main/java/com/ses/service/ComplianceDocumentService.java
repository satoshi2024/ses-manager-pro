package com.ses.service;

import com.ses.dto.compliance.ComplianceDocumentDeliveryDto;
import com.ses.dto.compliance.ComplianceDocumentGenerateRequest;

import java.util.List;

/**
 * 法定帳票の生成・交付・受領確認・ダウンロード（T064 B1）。
 *  - 生成はprofile→snapshot（append-only・CAS）→帳票PDF→document archive登録→交付記録の順。
 *  - 冪等キーは(contract_id, document_type, template_version, snapshot_hash)。
 *    同じsnapshotからの再生成は2件目を作らない（design §5.4）。
 *  - confirmed_at IS NULL は「受領未確認」（未交付ではない、design §5.1）。
 *  - export/download/PDFは画面と同じfield permission（R4.2）。営業は生成・交付・ダウンロード不可。
 */
public interface ComplianceDocumentService {

    List<ComplianceDocumentDeliveryDto> list(Long contractId);

    ComplianceDocumentDeliveryDto generate(Long contractId, ComplianceDocumentGenerateRequest request);

    ComplianceDocumentDeliveryDto confirm(Long contractId, Long deliveryId, String note);

    /** 生成済みPDFを返す（scanStatus CLEAN確認・アクセスログ記録済み）。 */
    byte[] download(Long contractId, Long deliveryId);
}
