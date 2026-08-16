package com.ses.service.portal;

import com.ses.dto.portal.PortalAcceptanceDto;
import com.ses.dto.portal.PortalContractDto;
import com.ses.dto.portal.PortalInvoiceDto;
import com.ses.dto.portal.PortalInvoiceRegisterRequest;
import com.ses.dto.portal.PortalQuotationDto;
import com.ses.dto.portal.PortalSalesOrderDto;
import com.ses.entity.Acceptance;

import java.io.InputStream;
import java.util.Locale;

/**
 * 顧客ポータルサービス（R2）。全メソッドは expectedCustomerId（portal orgのcustomer_id）を
 * SQL境界条件として使い、自組織分のみを返す（design §6.2・R4.3）。取得後checkにしない。
 */
public interface PortalCustomerService {

    // ===== 見積（G8 allow-list: 見積） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalQuotationDto> quotations(
            long current, long size, Long customerId);

    byte[] quotationPdf(Long quotationId, Long customerId, Locale locale);

    // ===== 注文請（G8 allow-list: 注文請） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalSalesOrderDto> salesOrders(
            long current, long size, Long customerId);

    /** 注文請PDF（文書台帳正本。未発行なら404）。 */
    InputStream acknowledgementPdf(Long orderId, Long customerId);

    // ===== 契約（G8 allow-list: 契約。金額は公開しない） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalContractDto> contracts(
            long current, long size, Long customerId, String status);

    PortalContractDto contract(Long contractId, Long customerId);

    /** 締結済み契約書PDF（文書台帳・CLEAN後のみ）。 */
    InputStream contractDocumentPdf(Long contractId, Long customerId);

    // ===== 作業報告・検収（G8 allow-list: 作業報告/検収。AcceptanceServiceへ委譲） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalAcceptanceDto> acceptances(
            long current, long size, Long customerId, String workMonth, String status);

    PortalAcceptanceDto acceptance(Long acceptanceId, Long customerId);

    /** 検収（提出済→検収済。order specの状態CAS。portal側で独自状態機械を作らない） */
    Acceptance portalAccept(Long acceptanceId, Long customerContactId, Long customerId);

    /** 差戻し（提出済→差戻し。理由必須） */
    Acceptance portalReject(Long acceptanceId, String comment, Long customerId);

    /** 検収書原本PDF（archive CLEAN後のみ）。 */
    InputStream acceptanceDocumentPdf(Long acceptanceId, Long customerId);

    // ===== 請求（G8 allow-list: 請求。入金済状態の変更APIは存在させない: R2.3） =====
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<PortalInvoiceDto> invoices(
            long current, long size, Long customerId);

    PortalInvoiceDto invoice(Long invoiceId, Long customerId);

    byte[] invoicePdf(Long invoiceId, Long customerId, Locale locale);

    /** 受領確認・支払予定日・問い合わせの登録（R2.3）。 */
    PortalInvoiceDto registerInvoice(Long invoiceId, Long customerId, PortalInvoiceRegisterRequest request);
}
