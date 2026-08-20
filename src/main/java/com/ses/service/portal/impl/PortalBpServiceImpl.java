package com.ses.service.portal.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.dto.bpcompany.BpBankAccountDto;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.dto.portal.PortalBpAvailabilityDto;
import com.ses.dto.portal.PortalBpAvailabilityRequest;
import com.ses.dto.portal.PortalBpBankAccountRequest;
import com.ses.dto.portal.PortalBpPaymentDto;
import com.ses.dto.portal.PortalBpSubmissionDto;
import com.ses.entity.BpAvailability;
import com.ses.entity.BpCompany;
import com.ses.entity.BpTerms;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpTermsResolver;
import com.ses.service.DocumentService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import com.ses.service.portal.PortalBpService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * BPポータルサービス実装。全クエリはbpCompanyId（portal org）をSQL境界に含める（design §6.2）。
 * 金額・支払状態の変更APIは存在させない（R3.3）。口座変更は承認engineへ委譲（design §3・R3.4）。
 */
@Service
@RequiredArgsConstructor
public class PortalBpServiceImpl implements PortalBpService {

    /** 空き要員: portal提出（内部review待ち） */
    public static final String AVAILABILITY_PENDING = "未確認";
    /** 空き要員: 内部reviewで却下 */
    public static final String AVAILABILITY_REJECTED = "却下";
    /** 空き要員: 有効（内部候補に出る） */
    public static final String AVAILABILITY_ACTIVE = "提案可能";
    /** 空き要員: 停止 */
    public static final String AVAILABILITY_EXPIRED = "失効";

    public static final String LINK_TARGET_BP_PAYMENT = "BP_PAYMENT";

    private final BpAvailabilityMapper availabilityMapper;
    private final BpPaymentMapper paymentMapper;
    private final BpCompanyMapper bpCompanyMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentMapper documentMapper;
    private final BpCompanyService bpCompanyService;
    private final BpTermsResolver bpTermsResolver;
    private final DocumentService documentService;
    private final ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;
    private final Clock clock;

    // ===== 空き要員 =====

    @Override
    public Page<PortalBpAvailabilityDto> availabilities(long current, long size, Long bpCompanyId) {
        if (bpCompanyId == null) {
            Page<PortalBpAvailabilityDto> empty = PageUtils.safePage(current, size);
            return new Page<>(empty.getCurrent(), empty.getSize(), 0);
        }
        Page<BpAvailability> page = availabilityMapper.selectPage(
                PageUtils.safePage(current, size),
                new LambdaQueryWrapper<BpAvailability>()
                        .eq(BpAvailability::getBpCompanyId, bpCompanyId)
                        .orderByDesc(BpAvailability::getId));
        Page<PortalBpAvailabilityDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toAvailabilityDto).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalBpAvailabilityDto createAvailability(Long bpCompanyId, PortalBpAvailabilityRequest request) {
        BpAvailability entity = new BpAvailability();
        entity.setBpCompanyId(bpCompanyId);
        BpCompany company = bpCompanyMapper.selectById(bpCompanyId);
        entity.setBpCompany(company == null ? null : company.getLegalName());
        entity.setInitialName(request.getInitialName().trim());
        entity.setSkillsJson(request.getSkillsJson());
        entity.setUnitPrice(request.getUnitPrice() == null ? null : request.getUnitPrice().longValueExact());
        entity.setAvailableFrom(request.getAvailableFrom());
        entity.setExperienceYears(request.getExperienceYears());
        entity.setStatus(AVAILABILITY_PENDING);
        entity.setRemarks(request.getRemarks());
        availabilityMapper.insert(entity);
        return toAvailabilityDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalBpAvailabilityDto updateAvailability(Long availabilityId, Long bpCompanyId,
                                                      PortalBpAvailabilityRequest request) {
        BpAvailability existing = availabilityMapper.selectOne(new LambdaQueryWrapper<BpAvailability>()
                .eq(BpAvailability::getId, availabilityId)
                .eq(BpAvailability::getBpCompanyId, bpCompanyId));
        if (existing == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // review済み（提案可能）の行はBP側から内容変更できない（停止のみ可能）
        if (!java.util.Set.of(AVAILABILITY_PENDING, AVAILABILITY_REJECTED).contains(existing.getStatus())) {
            throw BusinessException.of(409, "error.portal.bp.availabilityReviewed");
        }
        existing.setInitialName(request.getInitialName().trim());
        existing.setSkillsJson(request.getSkillsJson());
        existing.setUnitPrice(request.getUnitPrice() == null ? null : request.getUnitPrice().longValueExact());
        existing.setAvailableFrom(request.getAvailableFrom());
        existing.setExperienceYears(request.getExperienceYears());
        existing.setRemarks(request.getRemarks());
        // 却下後の編集は再提出（未確認へ戻す）
        if (AVAILABILITY_REJECTED.equals(existing.getStatus())) {
            existing.setStatus(AVAILABILITY_PENDING);
        }
        availabilityMapper.updateById(existing);
        return toAvailabilityDto(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stopAvailability(Long availabilityId, Long bpCompanyId) {
        int updated = availabilityMapper.update(null, new UpdateWrapper<BpAvailability>()
                .eq("id", availabilityId)
                .eq("bp_company_id", bpCompanyId)
                .eq("status", AVAILABILITY_ACTIVE)
                .set("status", AVAILABILITY_EXPIRED));
        if (updated == 0) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    // ===== 発注・作業実績 =====

    @Override
    public Page<PortalBpPaymentDto> payments(long current, long size, Long bpCompanyId, String status) {
        if (bpCompanyId == null) {
            Page<PortalBpPaymentDto> empty = PageUtils.safePage(current, size);
            return new Page<>(empty.getCurrent(), empty.getSize(), 0);
        }
        Page<PortalBpPaymentDto> page = paymentMapper.selectPortalPageDto(
                PageUtils.safePage(current, size), bpCompanyId, status);
        page.getRecords().forEach(dto -> {
            dto.setPaymentScheduleDate(estimatePaymentDate(bpCompanyId));
            dto.setSubmissionCount(documentLinkMapper.findDocumentIdsByTarget(LINK_TARGET_BP_PAYMENT, dto.getId()).size());
        });
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long paymentId, Long bpCompanyId) {
        // SQL境界で自社分のみ（他BPのIDは404秘匿。R4.3）
        requirePayment(paymentId, bpCompanyId);
        int updated = paymentMapper.confirmReceipt(paymentId, bpCompanyId, LocalDateTime.now(clock));
        if (updated == 0) {
            throw BusinessException.of(409, "error.portal.bp.receiptAlreadyConfirmed");
        }
    }

    // ===== 提出物 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalBpSubmissionDto submitDocument(Long paymentId, Long bpCompanyId, String originalName,
                                                String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw BusinessException.of(400, "error.portal.bp.documentRequired");
        }
        if (content.length > 10L * 1024 * 1024) {
            throw BusinessException.of(400, "error.portal.bp.documentTooLarge");
        }
        // SQL境界（id AND bp_company_id）で対象行を解決（他BPのIDは404秘匿。R4.3）
        PortalBpPaymentDto scope = paymentMapper.selectPortalDetailById(paymentId, bpCompanyId);
        if (scope == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 同一内容の再送は冪等に同一文書を返す（S13-R1-P2-04: businessKey=paymentId:contentHash）
        String contentHash = com.ses.common.util.SecurityHashUtil.sha256(
                java.util.Base64.getEncoder().encodeToString(content));
        String businessKey = "BP_PORTAL_SUBMISSION:" + paymentId + ":" + contentHash;
        // t_document_version.created_by はNOT NULL。portal principalには内部user IDがないため、
        // BPの担当営業（内部user）を明示的に作成者として指定する（監査の一貫性。R4.2）
        BpCompany company = bpCompanyMapper.selectById(bpCompanyId);
        DocumentRegisterRequest req = DocumentRegisterRequest.builder()
                .documentType("BP_SUBMISSION")
                .title("BP提出物: " + (scope.getWorkMonth() == null ? "未確定" : scope.getWorkMonth()))
                .counterpartyType("BP")
                .counterpartyId(bpCompanyId)
                .counterpartyNameSnapshot(null)
                .transactionDate(LocalDate.now(clock))
                .amount(scope.getAmount())
                .direction("INCOMING")
                .sourceType("RECEIVED")
                .originalName(originalName)
                .contentType(contentType)
                .businessKey(businessKey)
                .versionDiscriminator("1")
                .createdBy(company == null ? null : company.getPrimarySalesUserId())
                .targetType(LINK_TARGET_BP_PAYMENT)
                .targetId(paymentId)
                .build();
        com.ses.entity.Document doc;
        try (InputStream is = new ByteArrayInputStream(content)) {
            doc = documentService.registerReceived(req, is);
        } catch (java.io.IOException e) {
            throw BusinessException.of(500, "error.portal.bp.documentSaveFailed");
        }
        // S13-R1-P2-04: portalはconfirmしない（DRAFTのまま。内部側のreview後に確定する）。
        // download gateはscan CLEANのみでDRAFTでも利用可能。
        return toSubmissionDto(doc.getId(), doc.getTitle(), originalName, true);
    }

    @Override
    public List<PortalBpSubmissionDto> submissions(Long paymentId, Long bpCompanyId) {
        requirePayment(paymentId, bpCompanyId);
        return documentLinkMapper.findDocumentIdsByTarget(LINK_TARGET_BP_PAYMENT, paymentId).stream()
                .map(documentId -> {
                    DocumentVersion latest = documentVersionMapper.findLatestByDocumentId(documentId);
                    com.ses.entity.Document doc = documentMapper.selectById(documentId);
                    boolean clean = latest != null && "CLEAN".equals(latest.getScanStatus());
                    return toSubmissionDto(documentId, doc == null ? null : doc.getTitle(),
                            latest == null ? null : latest.getOriginalName(), clean);
                }).toList();
    }

    @Override
    public InputStream downloadSubmission(Long documentId, Long paymentId, Long bpCompanyId) {
        requirePayment(paymentId, bpCompanyId);
        List<Long> documentIds = documentLinkMapper.findDocumentIdsByTarget(LINK_TARGET_BP_PAYMENT, paymentId);
        if (!documentIds.contains(documentId)) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return documentService.download(documentId, null);
    }

    // ===== 支払状況 =====

    @Override
    public PortalBpPaymentDto payment(Long paymentId, Long bpCompanyId) {
        // SQL境界（id AND bp_company_id）で1行解決（S13-R1-P1-02: 一覧の並びに依存しない）
        PortalBpPaymentDto dto = paymentMapper.selectPortalDetailById(paymentId, bpCompanyId);
        if (dto == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dto.setPaymentScheduleDate(estimatePaymentDate(bpCompanyId));
        dto.setSubmissionCount(documentLinkMapper.findDocumentIdsByTarget(LINK_TARGET_BP_PAYMENT, paymentId).size());
        return dto;
    }

    // ===== 口座変更申請 =====

    @Override
    public List<BpBankAccountDto> bankAccounts(Long bpCompanyId) {
        List<BpBankAccountDto> accounts = bpCompanyService.getBankAccounts(bpCompanyId);
        // 内部user ID（approvedBy）をportalへ露出しない（S13-R1-P2-13）
        accounts.forEach(account -> account.setApprovedBy(null));
        return accounts;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestBankAccountChange(Long bpCompanyId, PortalBpBankAccountRequest request) {
        BpCompany company = bpCompanyMapper.selectById(bpCompanyId);
        if (company == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        // 口座はPENDINGとして登録（暗号化・マスク保存。支払先としては未反映）→ 承認engineへ申請
        BpBankAccountDto account = bpCompanyService.addBankAccount(bpCompanyId,
                request.getBankName().trim(), request.getBranchName().trim(), request.getAccountType(),
                request.getAccountNumber().trim(), request.getAccountHolder().trim(), null, null);
        // 申請者=BPの担当営業（内部ユーザー）。未割当なら申請不可（R3.4の内部承認者フロー）
        Long applicantId = company.getPrimarySalesUserId();
        if (applicantId == null) {
            throw BusinessException.of(400, "error.portal.bp.noSalesOwner");
        }
        approvalTargetAdapterRegistry.request(
                "bp_bank_account.change", "BP_BANK_ACCOUNT", account.getId(), java.util.Map.of(), applicantId);
    }

    // ===== ヘルパー =====

    private void requirePayment(Long paymentId, Long bpCompanyId) {
        com.ses.entity.BpPayment payment = paymentMapper.selectOne(new LambdaQueryWrapper<com.ses.entity.BpPayment>()
                .eq(com.ses.entity.BpPayment::getId, paymentId)
                .eq(com.ses.entity.BpPayment::getBpCompanyId, bpCompanyId));
        if (payment == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    private PortalBpAvailabilityDto toAvailabilityDto(BpAvailability a) {
        return PortalBpAvailabilityDto.builder()
                .id(a.getId())
                .initialName(a.getInitialName())
                .skillsJson(a.getSkillsJson())
                .unitPrice(a.getUnitPrice() == null ? null : java.math.BigDecimal.valueOf(a.getUnitPrice()))
                .availableFrom(a.getAvailableFrom())
                .experienceYears(a.getExperienceYears())
                .status(a.getStatus())
                .remarks(a.getRemarks())
                .createdAt(a.getCreatedAt())
                .build();
    }

    private PortalBpSubmissionDto toSubmissionDto(Long documentId, String title, String originalName,
                                                  boolean downloadable) {
        return PortalBpSubmissionDto.builder()
                .documentId(documentId)
                .title(title)
                .originalName(originalName)
                .downloadable(downloadable)
                .build();
    }

    /** 支払予定日の概算（取引条件: 締め日/オフセット/支払日。既存のBpComplianceServiceImplと同じ式）。 */
    private String estimatePaymentDate(Long bpCompanyId) {
        BpTerms terms = bpTermsResolver.resolveTermsAsOf(bpCompanyId, LocalDate.now(clock));
        if (terms == null || terms.getPaymentMonthOffset() == null || terms.getPaymentDay() == null) {
            return null;
        }
        LocalDate base = LocalDate.now(clock);
        int closingDay = terms.getClosingDay() == null ? 31 : terms.getClosingDay();
        LocalDate ref = closingDay >= 28
                ? base.withDayOfMonth(base.lengthOfMonth())
                : base.withDayOfMonth(Math.min(closingDay, base.lengthOfMonth()));
        LocalDate due = ref.plusMonths(terms.getPaymentMonthOffset())
                .withDayOfMonth(Math.min(terms.getPaymentDay(),
                        ref.plusMonths(terms.getPaymentMonthOffset()).lengthOfMonth()));
        return due.toString();
    }
}
