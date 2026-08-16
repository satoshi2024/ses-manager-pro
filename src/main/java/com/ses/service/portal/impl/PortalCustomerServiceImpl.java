package com.ses.service.portal.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.constant.StatusConstants;
import com.ses.dto.portal.PortalAcceptanceDto;
import com.ses.dto.portal.PortalContractDto;
import com.ses.dto.portal.PortalInvoiceDto;
import com.ses.dto.portal.PortalInvoiceRegisterRequest;
import com.ses.dto.portal.PortalQuotationDto;
import com.ses.dto.portal.PortalSalesOrderDto;
import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.Invoice;
import com.ses.entity.Quotation;
import com.ses.entity.SalesOrder;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.DocumentService;
import com.ses.service.InvoiceService;
import com.ses.service.InvoicePdfService;
import com.ses.service.QuotationPdfService;
import com.ses.service.portal.PortalCustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 顧客ポータルサービス実装。全クエリはcustomerId（portal org）をSQL境界に含める（design §6.2）。
 * 検収はAcceptanceService（order specの状態CAS）へ委譲し、portal側で独自状態機械を作らない（design §6.3）。
 */
@Service
@RequiredArgsConstructor
public class PortalCustomerServiceImpl implements PortalCustomerService {

    private static final List<String> VISIBLE_QUOTATION_STATUSES =
            List.of("提出済", "受注", "失注");
    private static final List<String> VISIBLE_INVOICE_STATUSES =
            List.of("送付済", "一部入金", "入金済");
    /** 注文請は「注文請提出」以降のみ公開（下書き・受領確認は社内検討中のため非公開） */
    private static final List<String> VISIBLE_SALES_ORDER_STATUSES =
            List.of("注文請提出", "契約化", "完了");

    private final QuotationMapper quotationMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final ContractMapper contractMapper;
    private final InvoiceMapper invoiceMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final AcceptanceService acceptanceService;
    private final DocumentService documentService;
    private final QuotationPdfService quotationPdfService;
    private final InvoicePdfService invoicePdfService;
    private final InvoiceService invoiceService;
    private final com.ses.service.cloudsign.CloudSignStatusMapper cloudSignStatusMapper;
    private final Clock clock;

    // ===== 見積 =====

    @Override
    public Page<PortalQuotationDto> quotations(long current, long size, Long customerId) {
        if (customerId == null) {
            return new Page<>(current, Math.min(size, 1000), 0);
        }
        Page<Quotation> page = quotationMapper.selectPage(
                new Page<>(current, Math.min(size, 1000)),
                new LambdaQueryWrapper<Quotation>()
                        .eq(Quotation::getCustomerId, customerId)
                        .in(Quotation::getStatus, VISIBLE_QUOTATION_STATUSES)
                        .orderByDesc(Quotation::getId));
        Page<PortalQuotationDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(q -> PortalQuotationDto.builder()
                .id(q.getId())
                .quotationNo(q.getQuotationNo())
                .title(q.getTitle())
                .status(q.getStatus())
                .unitPrice(q.getUnitPrice())
                .settlementHoursMin(q.getSettlementHoursMin())
                .settlementHoursMax(q.getSettlementHoursMax())
                .validUntil(q.getValidUntil())
                .remarks(q.getRemarks())
                .createdAt(q.getCreatedAt())
                .build()).toList());
        return result;
    }

    @Override
    public byte[] quotationPdf(Long quotationId, Long customerId, Locale locale) {
        Quotation quotation = quotationMapper.selectOne(new LambdaQueryWrapper<Quotation>()
                .eq(Quotation::getId, quotationId)
                .eq(Quotation::getCustomerId, customerId));
        if (quotation == null || !VISIBLE_QUOTATION_STATUSES.contains(quotation.getStatus())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return quotationPdfService.generate(quotation, locale);
    }

    // ===== 注文請 =====

    @Override
    public Page<PortalSalesOrderDto> salesOrders(long current, long size, Long customerId) {
        if (customerId == null) {
            return new Page<>(current, Math.min(size, 1000), 0);
        }
        // field-inventory §3.1: 注文請は「注文請提出済み行」のみ公開（S13-R1-P2-03）
        Page<SalesOrder> page = salesOrderMapper.selectPage(
                new Page<>(current, Math.min(size, 1000)),
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getCustomerId, customerId)
                        .in(SalesOrder::getStatus, VISIBLE_SALES_ORDER_STATUSES)
                        .orderByDesc(SalesOrder::getId));
        Page<PortalSalesOrderDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(o -> PortalSalesOrderDto.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .customerPoNo(o.getCustomerPoNo())
                .orderDate(o.getOrderDate())
                .startDate(o.getStartDate())
                .endDate(o.getEndDate())
                .status(o.getStatus())
                .totalAmountSnapshot(o.getTotalAmountSnapshot())
                .paymentTermsSnapshot(o.getPaymentTermsSnapshot())
                .createdAt(o.getCreatedAt())
                .acknowledgementAvailable(o.getAcknowledgementDocumentId() != null)
                .build()).toList());
        return result;
    }

    @Override
    public InputStream acknowledgementPdf(Long orderId, Long customerId) {
        SalesOrder order = salesOrderMapper.selectOne(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getId, orderId)
                .eq(SalesOrder::getCustomerId, customerId));
        if (order == null || order.getAcknowledgementDocumentId() == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 文書が本当にこの注文へリンクされているか（二重認可。R4.3）
        requireLink(order.getAcknowledgementDocumentId(), "SALES_ORDER", orderId);
        return documentService.download(order.getAcknowledgementDocumentId(), null);
    }

    // ===== 契約 =====

    @Override
    public Page<PortalContractDto> contracts(long current, long size, Long customerId, String status) {
        if (customerId == null) {
            return new Page<>(current, Math.min(size, 1000), 0);
        }
        return contractMapper.selectPortalPageDto(new Page<>(current, Math.min(size, 1000)), customerId, status);
    }

    @Override
    public PortalContractDto contract(Long contractId, Long customerId) {
        PortalContractDto dto = contractMapper.selectPortalDetailDto(contractId, customerId);
        if (dto == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dto.setEsignStatus(cloudSignStatusMapper.businessStatus(dto.getEsignStatus() == null
                ? null : parseProviderStatus(dto.getEsignStatus())));
        return dto;
    }

    @Override
    public InputStream contractDocumentPdf(Long contractId, Long customerId) {
        PortalContractDto dto = contractMapper.selectPortalDetailDto(contractId, customerId);
        if (dto == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        List<Long> documentIds = documentLinkMapper.findDocumentIdsByTarget("CONTRACT", contractId);
        if (documentIds.isEmpty()) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        for (Long documentId : documentIds) {
            try {
                return documentService.download(documentId, null);
            } catch (BusinessException e) {
                // scan未完了などで開けない文書は次を試す（最終的に404/403）
            }
        }
        throw BusinessException.of(404, "error.scope.notFound");
    }

    // ===== 作業報告・検収 =====

    @Override
    public Page<PortalAcceptanceDto> acceptances(long current, long size, Long customerId,
                                                 String workMonth, String status) {
        return acceptanceService.portalPage(current, size, customerId, workMonth, status);
    }

    @Override
    public PortalAcceptanceDto acceptance(Long acceptanceId, Long customerId) {
        Acceptance acceptance = acceptanceService.portalGet(acceptanceId, customerId);
        return toDto(acceptance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance portalAccept(Long acceptanceId, Long customerContactId, Long customerId) {
        return acceptanceService.portalAccept(acceptanceId, customerContactId, customerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance portalReject(Long acceptanceId, String comment, Long customerId) {
        return acceptanceService.portalReject(acceptanceId, comment, customerId);
    }

    @Override
    public InputStream acceptanceDocumentPdf(Long acceptanceId, Long customerId) {
        Acceptance acceptance = acceptanceService.portalGet(acceptanceId, customerId);
        if (acceptance.getDocumentId() == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 検収書は契約へリンク済み（registerReceived時のtargetType=CONTRACT）。二重認可（R4.3）
        requireLink(acceptance.getDocumentId(), "CONTRACT", acceptance.getContractId());
        return documentService.download(acceptance.getDocumentId(), null);
    }

    // ===== 請求 =====

    @Override
    public Page<PortalInvoiceDto> invoices(long current, long size, Long customerId) {
        if (customerId == null) {
            return new Page<>(current, Math.min(size, 1000), 0);
        }
        Page<Invoice> page = invoiceMapper.selectPage(
                new Page<>(current, Math.min(size, 1000)),
                new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getCustomerId, customerId)
                        .in(Invoice::getStatus, VISIBLE_INVOICE_STATUSES)
                        .orderByDesc(Invoice::getId));
        Page<PortalInvoiceDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toInvoiceDto).toList());
        return result;
    }

    @Override
    public PortalInvoiceDto invoice(Long invoiceId, Long customerId) {
        Invoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .eq(Invoice::getCustomerId, customerId)
                .in(Invoice::getStatus, VISIBLE_INVOICE_STATUSES));
        if (invoice == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return toInvoiceDto(invoice);
    }

    @Override
    public byte[] invoicePdf(Long invoiceId, Long customerId, Locale locale) {
        Invoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .eq(Invoice::getCustomerId, customerId)
                .in(Invoice::getStatus, VISIBLE_INVOICE_STATUSES));
        if (invoice == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return invoicePdfService.generate(invoiceService.detail(invoiceId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalInvoiceDto registerInvoice(Long invoiceId, Long customerId, PortalInvoiceRegisterRequest request) {
        Invoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .eq(Invoice::getCustomerId, customerId)
                .in(Invoice::getStatus, VISIBLE_INVOICE_STATUSES));
        if (invoice == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 入金済状態は変更できない（R2.3）。portalが変更できるのは登録3項目のみ。
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Invoice> update =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Invoice>()
                        .eq("id", invoiceId)
                        .eq("version", invoice.getVersion());
        if (Boolean.TRUE.equals(request.getReceivedConfirmed()) && invoice.getReceivedConfirmedAt() == null) {
            update.set("received_confirmed_at", LocalDateTime.now(clock));
        }
        if (request.getPaymentExpectedDate() != null) {
            update.set("payment_expected_date", request.getPaymentExpectedDate());
        }
        if (request.getInquiry() != null) {
            update.set("portal_inquiry",
                    StringUtils.hasText(request.getInquiry()) ? request.getInquiry().trim() : null);
        }
        int updated = invoiceMapper.update(null, update);
        if (updated == 0) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toInvoiceDto(invoiceMapper.selectById(invoiceId));
    }

    // ===== ヘルパー =====

    private PortalAcceptanceDto toDto(Acceptance a) {
        return PortalAcceptanceDto.builder()
                .id(a.getId())
                .workMonth(a.getWorkMonth())
                .status(a.getStatus())
                .submittedAt(a.getSubmittedAt())
                .acceptedAt(a.getAcceptedAt())
                .rejectComment(a.getRejectComment())
                .hoursSnapshot(a.getHoursSnapshot())
                .amountSnapshot(a.getAmountSnapshot())
                .customerContactNameSnapshot(a.getCustomerContactNameSnapshot())
                .documentAvailable(a.getDocumentId() != null)
                .build();
    }

    private PortalInvoiceDto toInvoiceDto(Invoice i) {
        return PortalInvoiceDto.builder()
                .id(i.getId())
                .invoiceNo(i.getInvoiceNo())
                .billingMonth(i.getBillingMonth())
                .subtotal(i.getSubtotal())
                .tax(i.getTax())
                .total(i.getTotal())
                .taxRate(i.getTaxRate())
                .status(i.getStatus())
                .issuedDate(i.getIssuedDate())
                .dueDate(i.getDueDate())
                .receivedConfirmedAt(i.getReceivedConfirmedAt())
                .paymentExpectedDate(i.getPaymentExpectedDate())
                .portalInquiry(i.getPortalInquiry())
                .build();
    }

    private void requireLink(Long documentId, String targetType, Long targetId) {
        boolean linked = documentLinkMapper.findDocumentIdsByTarget(targetType, targetId).contains(documentId);
        if (!linked) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private Integer parseProviderStatus(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
