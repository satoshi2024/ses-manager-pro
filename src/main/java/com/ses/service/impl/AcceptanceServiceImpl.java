package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.acceptance.AcceptanceGridDto;
import com.ses.entity.Acceptance;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecord;
import com.ses.mapper.AcceptanceMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 月次検収サービス実装（order-acceptance-workflow / B1）。
 * 状態機械はdesign §5.3: 未提出→提出済→検収済/差戻し、差戻し→再提出、検収済→取消（承認必須）。
 * 提出時点のwork record工数・金額・更新日時をsnapshotし、以後の工数変更で検収額を変えない。
 */
@Service
@RequiredArgsConstructor
public class AcceptanceServiceImpl extends ServiceImpl<AcceptanceMapper, Acceptance>
        implements AcceptanceService {

    private final ContractMapper contractMapper;
    private final WorkRecordMapper workRecordMapper;
    private final CustomerContactMapper customerContactMapper;
    private final DataScopeService dataScopeService;
    private final com.ses.service.DocumentService documentService;

    @Override
    public Page<AcceptanceGridDto> pageGrid(long current, long size, String workMonth,
                                            String status, Long customerId, Long engineerId) {
        if (workMonth == null || workMonth.isBlank()) {
            throw BusinessException.of(400, "error.acceptance.workMonthRequired");
        }
        List<Long> contractIds = scopedContractIds();
        return baseMapper.selectGridPage(new Page<>(current, Math.min(size, 1000)),
                workMonth, status, customerId, engineerId, contractIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance submit(Long contractId, String workMonth) {
        Contract contract = contractId == null ? null : contractMapper.selectById(contractId);
        if (contract == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(contractId);
        if (Boolean.FALSE.equals(contract.getAcceptanceRequired())) {
            throw BusinessException.of(400, "error.acceptance.notRequired");
        }
        WorkRecord workRecord = workRecordMapper.selectOne(new LambdaQueryWrapper<WorkRecord>()
                .eq(WorkRecord::getContractId, contractId)
                .eq(WorkRecord::getWorkMonth, workMonth)
                .last("LIMIT 1"));
        if (workRecord == null || !StatusConstants.WORK_RECORD_CONFIRMED.equals(workRecord.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.workRecordNotConfirmed");
        }

        // 契約×月はUNIQUEで1件。行ロック＋状態CASで二重提出を防ぐ。
        Acceptance acceptance = baseMapper.selectByContractAndMonthForUpdate(contractId, workMonth);
        if (acceptance == null) {
            acceptance = new Acceptance();
            acceptance.setContractId(contractId);
            acceptance.setWorkMonth(workMonth);
            acceptance.setStatus(StatusConstants.ACCEPTANCE_UNSUBMITTED);
            acceptance.setWorkRecordId(workRecord.getId());
            baseMapper.insert(acceptance);
        }
        if (!Set.of(StatusConstants.ACCEPTANCE_UNSUBMITTED, StatusConstants.ACCEPTANCE_REJECTED)
                .contains(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.statusTransitionInvalid",
                    acceptance.getStatus(), StatusConstants.ACCEPTANCE_SUBMITTED);
        }
        applySnapshot(acceptance, workRecord);
        acceptance.setStatus(StatusConstants.ACCEPTANCE_SUBMITTED);
        acceptance.setSubmittedAt(LocalDateTime.now());
        acceptance.setRejectComment(null);
        baseMapper.updateById(acceptance);
        return acceptance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance accept(Long acceptanceId, Long customerContactId) {
        Acceptance acceptance = requireForUpdate(acceptanceId);
        assertAllowedAcceptance(acceptanceId);
        if (!StatusConstants.ACCEPTANCE_SUBMITTED.equals(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.statusTransitionInvalid",
                    acceptance.getStatus(), StatusConstants.ACCEPTANCE_ACCEPTED);
        }
        Contract contract = contractMapper.selectById(acceptance.getContractId());
        if (customerContactId != null) {
            validateContactBelongsToCustomer(customerContactId, contract == null ? null : contract.getCustomerId());
        }
        acceptance.setStatus(StatusConstants.ACCEPTANCE_ACCEPTED);
        acceptance.setCustomerContactId(customerContactId);
        acceptance.setAcceptedAt(LocalDateTime.now());
        acceptance.setRejectComment(null);
        baseMapper.updateById(acceptance);
        return acceptance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance reject(Long acceptanceId, String comment) {
        Acceptance acceptance = requireForUpdate(acceptanceId);
        assertAllowedAcceptance(acceptanceId);
        if (!StatusConstants.ACCEPTANCE_SUBMITTED.equals(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.statusTransitionInvalid",
                    acceptance.getStatus(), StatusConstants.ACCEPTANCE_REJECTED);
        }
        if (!StringUtils.hasText(comment)) {
            throw BusinessException.of(400, "error.acceptance.rejectCommentRequired");
        }
        acceptance.setStatus(StatusConstants.ACCEPTANCE_REJECTED);
        acceptance.setRejectComment(comment.trim());
        acceptance.setAcceptedAt(null);
        baseMapper.updateById(acceptance);
        return acceptance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Acceptance resubmit(Long acceptanceId) {
        Acceptance acceptance = requireForUpdate(acceptanceId);
        assertAllowedAcceptance(acceptanceId);
        if (!StatusConstants.ACCEPTANCE_REJECTED.equals(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.statusTransitionInvalid",
                    acceptance.getStatus(), StatusConstants.ACCEPTANCE_SUBMITTED);
        }
        WorkRecord workRecord = workRecordMapper.selectById(acceptance.getWorkRecordId());
        if (workRecord == null || !StatusConstants.WORK_RECORD_CONFIRMED.equals(workRecord.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.workRecordNotConfirmed");
        }
        // 再提出時点のsnapshotを取り直す（差戻し→再提出で最新値を反映）
        applySnapshot(acceptance, workRecord);
        acceptance.setStatus(StatusConstants.ACCEPTANCE_SUBMITTED);
        acceptance.setSubmittedAt(LocalDateTime.now());
        acceptance.setRejectComment(null);
        baseMapper.updateById(acceptance);
        return acceptance;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyCancellation(Long acceptanceId) {
        // 検収取消の承認適用（R3.4）。検収済→差戻しで再提出可能にする。
        Acceptance acceptance = requireForUpdate(acceptanceId);
        if (!StatusConstants.ACCEPTANCE_ACCEPTED.equals(acceptance.getStatus())) {
            throw BusinessException.of(409, "error.acceptance.statusTransitionInvalid",
                    acceptance.getStatus(), StatusConstants.ACCEPTANCE_REJECTED);
        }
        acceptance.setStatus(StatusConstants.ACCEPTANCE_REJECTED);
        acceptance.setRejectComment("検収取消（承認適用）");
        acceptance.setAcceptedAt(null);
        baseMapper.updateById(acceptance);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public com.ses.entity.Acceptance uploadDocument(Long acceptanceId, org.springframework.web.multipart.MultipartFile file) {
        Acceptance acceptance = require(acceptanceId);
        assertAllowedAcceptance(acceptanceId);
        if (acceptance.getDocumentId() != null) {
            throw BusinessException.of(409, "error.acceptance.documentAlreadyRegistered");
        }
        if (file == null || file.isEmpty()) {
            throw BusinessException.of(400, "error.acceptance.documentRequired");
        }
        if (file.getSize() > 10L * 1024 * 1024) {
            throw BusinessException.of(400, "error.acceptance.documentTooLarge");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw BusinessException.of(400, "error.acceptance.documentReadFailed");
        }
        Contract contract = contractMapper.selectById(acceptance.getContractId());
        com.ses.dto.document.DocumentRegisterRequest req =
                com.ses.dto.document.DocumentRegisterRequest.builder()
                        .documentType("ACCEPTANCE")
                        .title("検収書: " + (contract == null ? "" : contract.getContractNo())
                                + " / " + acceptance.getWorkMonth())
                        .documentNo(contract == null ? null : contract.getContractNo())
                        .counterpartyType("CUSTOMER")
                        .counterpartyId(contract == null ? null : contract.getCustomerId())
                        .counterpartyNameSnapshot(null)
                        .transactionDate(acceptance.getAcceptedAt() == null ? java.time.LocalDate.now()
                                : acceptance.getAcceptedAt().toLocalDate())
                        .amount(acceptance.getAmountSnapshot())
                        .direction("OUTGOING")
                        .originalName(file.getOriginalFilename())
                        .contentType(file.getContentType())
                        .sourceType("RECEIVED")
                        .businessKey("ACCEPTANCE:" + acceptance.getId())
                        .versionDiscriminator("1")
                        // 検収書は契約のscope（DataScope）で見せるためCONTRACTへリンクする
                        .targetType("CONTRACT")
                        .targetId(acceptance.getContractId())
                        .build();
        com.ses.entity.Document doc;
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(bytes)) {
            doc = documentService.registerReceived(req, is);
        } catch (java.io.IOException e) {
            throw BusinessException.of(500, "error.acceptance.documentSaveFailed");
        }
        documentService.confirm(doc.getId());
        this.update(new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Acceptance>()
                .eq("id", acceptanceId)
                .set("document_id", doc.getId()));
        return require(acceptanceId);
    }

    @Override
    public java.io.InputStream downloadDocument(Long acceptanceId) {
        Acceptance acceptance = require(acceptanceId);
        assertAllowedAcceptance(acceptanceId);
        if (acceptance.getDocumentId() == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return documentService.download(acceptance.getDocumentId(), null);
    }

    @Override
    public void assertAllowedAcceptance(Long acceptanceId) {
        Acceptance acceptance = require(acceptanceId);
        if (dataScopeService.isScoped()
                && !dataScopeService.allowedContractIds().contains(acceptance.getContractId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    // ===== 内部ヘルパー =====

    private void applySnapshot(Acceptance acceptance, WorkRecord workRecord) {
        acceptance.setWorkRecordId(workRecord.getId());
        acceptance.setHoursSnapshot(workRecord.getActualHours());
        acceptance.setAmountSnapshot(workRecord.getBillingAmount());
        acceptance.setWorkRecordUpdatedAt(workRecord.getUpdatedAt());
    }

    private void validateContactBelongsToCustomer(Long contactId, Long customerId) {
        com.ses.entity.CustomerContact contact = contactId == null ? null : customerContactMapper.selectById(contactId);
        if (contact == null || !Objects.equals(contact.getCustomerId(), customerId)) {
            throw BusinessException.of(400, "error.acceptance.contactInvalid");
        }
    }

    private List<Long> scopedContractIds() {
        if (!dataScopeService.isScoped()) {
            return null; // 全件
        }
        Set<Long> ids = dataScopeService.allowedContractIds();
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    private Acceptance require(Long id) {
        Acceptance acceptance = id == null ? null : baseMapper.selectById(id);
        if (acceptance == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return acceptance;
    }

    private Acceptance requireForUpdate(Long id) {
        Acceptance acceptance = id == null ? null : baseMapper.selectByIdForUpdate(id);
        if (acceptance == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return acceptance;
    }
}
