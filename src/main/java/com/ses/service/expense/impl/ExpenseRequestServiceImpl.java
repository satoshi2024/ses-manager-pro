package com.ses.service.expense.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.ExpenseRequest;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ExpenseRequestMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import com.ses.service.expense.ExpenseRequestService;
import com.ses.service.impl.ExpenseRequestApprovalAdapter;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 経費申請サービス実装（T091 / engineer-self-service-portal-v2 B1）。
 * 状態機械: 下書き→申請中→承認済→会計連携済→支払済（design §6.3）。
 * 状態遷移はstatus+versionの複合CASで実行し、承認後の変更（R3.3）をfail-closedにする。
 */
@Service
@RequiredArgsConstructor
public class ExpenseRequestServiceImpl implements ExpenseRequestService {

    private static final String LINK_MENU_KEY = "myExpenses";
    private static final String LINK_URL = "/my/expenses";
    private static final int MAX_DESCRIPTION_LENGTH = 1000;

    private final ExpenseRequestMapper expenseRequestMapper;
    private final EngineerMapper engineerMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;
    private final ApprovalEngineService approvalEngineService;
    private final DocumentService documentService;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final NotificationService notificationService;
    private final OrganizationScopeService organizationScopeService;
    private final java.time.Clock clock;

    // ----------------------------------------------------------------
    // 本人
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequestDto> pageForEngineer(Long engineerId, String status, long current, long size) {
        LambdaQueryWrapper<ExpenseRequest> query = new LambdaQueryWrapper<ExpenseRequest>()
                .eq(ExpenseRequest::getEngineerId, engineerId)
                .orderByDesc(ExpenseRequest::getId);
        if (status != null && !status.isBlank()) {
            query.eq(ExpenseRequest::getStatus, status);
        }
        Page<ExpenseRequest> page = expenseRequestMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto createDraft(Long engineerId, ExpenseDraftCommand command) {
        validateDraft(command);
        ExpenseRequest expense = ExpenseRequest.builder()
                .engineerId(engineerId)
                .expenseDate(command.expenseDate())
                .category(command.category())
                .amount(command.amount())
                .customerId(command.customerId())
                .projectId(command.projectId())
                .description(trimToNull(command.description()))
                .status(STATUS_DRAFT)
                .version(0)
                .build();
        expenseRequestMapper.insert(expense);
        return toDto(expense, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto updateDraft(Long engineerId, Long id, ExpenseDraftCommand command) {
        validateDraft(command);
        ExpenseRequest expense = requireOwned(engineerId, id);
        if (!STATUS_DRAFT.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition", expense.getStatus(), STATUS_DRAFT);
        }
        int version = value(expense.getVersion());
        int updated = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                .eq("id", id)
                .eq("status", STATUS_DRAFT)
                .eq("version", version)
                .set("expense_date", command.expenseDate())
                .set("category", command.category())
                .set("amount", command.amount())
                .set("customer_id", command.customerId())
                .set("project_id", command.projectId())
                .set("description", trimToNull(command.description()))
                .set("version", version + 1)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(requireOwned(engineerId, id), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long engineerId, Long id) {
        ExpenseRequest expense = requireOwned(engineerId, id);
        if (!STATUS_DRAFT.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition", expense.getStatus(), STATUS_DRAFT);
        }
        // 論理削除。添付済み領収書は文書台帳（t_document）の資産のため削除しない。
        expenseRequestMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto submit(Long engineerId, Long id) {
        ExpenseRequest expense = requireOwned(engineerId, id);
        if (!STATUS_DRAFT.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition", expense.getStatus(), STATUS_DRAFT);
        }
        // 申請受付（snapshot内で下書き遷移を検証。engine.requestは冪等キーで二重申請を返す）。
        ApprovalRequest approval = approvalTargetAdapterRegistry.request(
                ExpenseRequestApprovalAdapter.REQUEST_TYPE, "EXPENSE_REQUEST", id,
                Map.of("action", "submit"), SecurityUtils.currentUserId());

        int version = value(expense.getVersion());
        int updated = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                .eq("id", id)
                .eq("status", STATUS_DRAFT)
                .eq("version", version)
                .set("status", STATUS_APPLIED)
                .set("approval_request_id", approval.getId())
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }

        // 経費番号採番 EX-{id}（null時のみ。UNIQUE衝突は409）。
        ExpenseRequest current = requireOwned(engineerId, id);
        if (current.getExpenseNo() == null || current.getExpenseNo().isBlank()) {
            try {
                int numbered = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                        .eq("id", id)
                        .set("expense_no", "EX-" + id)
                        .set("updated_at", LocalDateTime.now(clock)));
                if (numbered != 1) {
                    throw BusinessException.of(409, "error.common.optimisticLock");
                }
            } catch (DuplicateKeyException e) {
                throw BusinessException.of(409, "error.expense.duplicateNo");
            }
        }
        return toDto(requireOwned(engineerId, id), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto resubmit(Long engineerId, Long id) {
        ExpenseRequest expense = requireOwned(engineerId, id);
        if (!STATUS_APPLIED.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition", expense.getStatus(), STATUS_APPLIED);
        }
        if (expense.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "error.expense.notReturned");
        }
        ApprovalRequest approval = approvalRequestMapper.selectById(expense.getApprovalRequestId());
        if (approval == null
                || (!"returned".equals(approval.getStatus()) && !"conflict".equals(approval.getStatus()))) {
            throw BusinessException.of(400, "error.expense.notReturned");
        }
        approvalEngineService.resubmit(approval.getId(), SecurityUtils.currentUserId(), null, null, null);
        return toDto(requireOwned(engineerId, id), null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto attachReceipt(Long engineerId, Long id, String originalName, String contentType,
                                           InputStream content) {
        ExpenseRequest expense = requireOwned(engineerId, id);
        assertReceiptChangeable(expense);
        if (originalName == null || originalName.isBlank()
                || contentType == null || contentType.isBlank()) {
            throw BusinessException.of(400, "error.expense.receiptRequired");
        }
        if (content == null) {
            throw BusinessException.of(400, "error.file.empty");
        }
        // 文書台帳へ登録（scan fail-closed: CLEAN以外はregisterReceivedが拒否）。
        // businessKey冪等のため同じ経費への再添付は既存文書を返す。
        // ※ リンクはDocumentServiceImpl.link経由でなく直接insertする
        //   （H2のt_document_linkにskill_sheet列が無い場合でもSELECTが壊れない。UNIQUEで冪等）。
        Document document = documentService.registerReceived(DocumentRegisterRequest.builder()
                .documentType("RECEIPT")
                .title("経費領収書 EX-" + id)
                .sourceType("RECEIVED")
                .direction("INCOMING")
                .counterpartyType("INTERNAL")
                .transactionDate(expense.getExpenseDate())
                .amount(expense.getAmount())
                .businessKey("EXPENSE_RECEIPT:" + id)
                .originalName(originalName)
                .contentType(contentType)
                .build(), content);
        // FileScopeValidationServiceのRECEIPT規則（本人/管理者/マネージャー配下のみ）は
        // ENGINEERリンクから要員を解決するため、領収書には必ずENGINEERリンクを付ける。
        insertDocumentLink(document.getId(), "ENGINEER", expense.getEngineerId());
        insertDocumentLink(document.getId(), "EXPENSE_REQUEST", id);

        int version = value(expense.getVersion());
        int updated = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                .eq("id", id)
                .eq("status", expense.getStatus())
                .eq("version", version)
                .set("receipt_document_id", document.getId())
                .set("version", version + 1)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(requireOwned(engineerId, id), null, null, null);
    }

    /** DocumentLinkへ直接insertする（UNIQUE(document_id,target_type,target_id)で冪等）。 */
    private void insertDocumentLink(Long documentId, String targetType, Long targetId) {
        if (documentId == null || targetType == null || targetId == null) {
            return;
        }
        DocumentLink link = new DocumentLink();
        link.setDocumentId(documentId);
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        try {
            documentLinkMapper.insert(link);
        } catch (DuplicateKeyException e) {
            // 既にリンク済み（冪等）。
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptDownload downloadReceipt(Long engineerId, Long id) {
        ExpenseRequest expense = requireOwned(engineerId, id);
        if (expense.getReceiptDocumentId() == null) {
            throw BusinessException.of(404, "error.expense.receiptNotFound");
        }
        // scan=CLEANの最新版のcontentType/元ファイル名を解決する（fail-closed: CLEAN以外は拒否）。
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocumentId, expense.getReceiptDocumentId())
                        .orderByDesc(DocumentVersion::getVersionNo)
                        .last("LIMIT 1"));
        DocumentVersion version = versions.isEmpty() ? null : versions.get(0);
        if (version == null || version.getScanStatus() == null || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        InputStream stream = documentService.download(expense.getReceiptDocumentId(), null);
        return new ReceiptDownload(stream,
                version.getContentType() == null ? "application/octet-stream" : version.getContentType(),
                version.getOriginalName() == null ? "receipt.pdf" : version.getOriginalName());
    }

    // ----------------------------------------------------------------
    // 管理
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequestDto> pageManagement(String engineerName, String status, long current, long size) {
        Set<Long> scopeIds = managementScopeEngineerIds();
        if (scopeIds != null && scopeIds.isEmpty()) {
            return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
        }
        LambdaQueryWrapper<ExpenseRequest> query = new LambdaQueryWrapper<ExpenseRequest>()
                .orderByDesc(ExpenseRequest::getId);
        if (scopeIds != null) {
            query.in(ExpenseRequest::getEngineerId, scopeIds);
        }
        if (status != null && !status.isBlank()) {
            query.eq(ExpenseRequest::getStatus, status);
        }
        if (engineerName != null && !engineerName.isBlank()) {
            Set<Long> nameIds = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                            .like(Engineer::getFullName, engineerName.trim()))
                    .stream().map(Engineer::getId).collect(Collectors.toSet());
            if (nameIds.isEmpty()) {
                return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
            }
            if (scopeIds != null) {
                nameIds.retainAll(scopeIds);
                if (nameIds.isEmpty()) {
                    return new Page<>(current <= 0 ? 1 : current,
                            size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
                }
            }
            query.in(ExpenseRequest::getEngineerId, nameIds);
        }
        Page<ExpenseRequest> page = expenseRequestMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpenseRequestDto detailManagement(Long id) {
        ExpenseRequest expense = require(id);
        assertManagementScope(expense.getEngineerId());
        return toDto(expense, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ExpenseRequestDto markPaid(Long id) {
        ExpenseRequest expense = require(id);
        assertManagementScope(expense.getEngineerId());
        if (!STATUS_ACCOUNTING_SENT.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.invalidTransition",
                    expense.getStatus(), STATUS_PAID);
        }
        int version = value(expense.getVersion());
        int updated = expenseRequestMapper.update(null, new UpdateWrapper<ExpenseRequest>()
                .eq("id", id)
                .eq("status", STATUS_ACCOUNTING_SENT)
                .eq("version", version)
                .set("status", STATUS_PAID)
                .set("paid_at", LocalDateTime.now(clock))
                .set("version", version + 1)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        notifyPaid(expense);
        return toDto(require(id), engineerNameOf(expense.getEngineerId()), null, null);
    }

    // ----------------------------------------------------------------
    // 内部
    // ----------------------------------------------------------------

    private void validateDraft(ExpenseDraftCommand command) {
        if (command == null || command.expenseDate() == null) {
            throw BusinessException.of(400, "error.expense.dateRequired");
        }
        if (command.category() == null || !CATEGORIES.contains(command.category())) {
            throw BusinessException.of(400, "error.expense.invalidCategory");
        }
        if (command.amount() == null || command.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.of(400, "error.expense.invalidAmount");
        }
        if (command.description() != null && command.description().length() > MAX_DESCRIPTION_LENGTH) {
            throw BusinessException.of(400, "error.expense.descriptionTooLong");
        }
    }

    /** 承認済以降の領収書差替え・内容変更を拒否する（R3.3）。 */
    private void assertReceiptChangeable(ExpenseRequest expense) {
        if (!STATUS_DRAFT.equals(expense.getStatus()) && !STATUS_APPLIED.equals(expense.getStatus())) {
            throw BusinessException.of(400, "error.expense.receiptLocked");
        }
    }

    private ExpenseRequest requireOwned(Long engineerId, Long id) {
        if (id == null || engineerId == null) {
            throw BusinessException.of(404, "error.expense.notFound");
        }
        ExpenseRequest expense = expenseRequestMapper.selectOne(new LambdaQueryWrapper<ExpenseRequest>()
                .eq(ExpenseRequest::getId, id)
                .eq(ExpenseRequest::getEngineerId, engineerId));
        if (expense == null) {
            throw BusinessException.of(404, "error.expense.notFound");
        }
        return expense;
    }

    private ExpenseRequest require(Long id) {
        ExpenseRequest expense = id == null ? null : expenseRequestMapper.selectById(id);
        if (expense == null) {
            throw BusinessException.of(404, "error.expense.notFound");
        }
        return expense;
    }

    /** 管理画面の母集団（design §6.2決定表）: 管理者=全件(null)、マネージャー=組織scope∩DataScope。 */
    private Set<Long> managementScopeEngineerIds() {
        String role = SecurityUtils.currentRole();
        if (!"管理者".equals(role) && !"マネージャー".equals(role)) {
            // controllerの@PreAuthorizeに加えてservice層でも明示的に拒否する（fail-closed）。
            throw BusinessException.of(403, "error.accessDenied");
        }
        if ("管理者".equals(role) || organizationScopeService.hasFullAccess()) {
            return null;
        }
        Set<Long> allowed = organizationScopeService.allowedEngineerIds(LocalDate.now(clock));
        return allowed == null ? Set.of() : new HashSet<>(allowed);
    }

    private void assertManagementScope(Long engineerId) {
        Set<Long> scopeIds = managementScopeEngineerIds();
        if (scopeIds == null) {
            return;
        }
        if (engineerId == null || !scopeIds.contains(engineerId)) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
    }

    private Page<ExpenseRequestDto> toDtoPage(Page<ExpenseRequest> page, boolean withEngineerName) {
        Set<Long> approvalIds = page.getRecords().stream()
                .map(ExpenseRequest::getApprovalRequestId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ApprovalRequest> approvals = approvalIds.isEmpty() ? Map.of()
                : approvalRequestMapper.selectBatchIds(approvalIds).stream()
                .collect(Collectors.toMap(ApprovalRequest::getId, Function.identity()));
        Map<Long, Integer> receiptVersions = latestReceiptVersionNos(page.getRecords().stream()
                .map(ExpenseRequest::getReceiptDocumentId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> engineerNames = withEngineerName
                ? engineerNameOf(page.getRecords().stream()
                        .map(ExpenseRequest::getEngineerId).collect(Collectors.toSet()))
                : Map.of();
        List<ExpenseRequestDto> dtos = page.getRecords().stream()
                .map(expense -> {
                    ApprovalRequest approval = expense.getApprovalRequestId() == null
                            ? null : approvals.get(expense.getApprovalRequestId());
                    return toDto(expense, engineerNames.get(expense.getEngineerId()), approval,
                            receiptVersions.get(expense.getReceiptDocumentId()));
                })
                .toList();
        Page<ExpenseRequestDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(dtos);
        return result;
    }

    /** 領収書文書の最新版番号（管理画面の/api/documentsダウンロード用）。 */
    private Map<Long, Integer> latestReceiptVersionNos(Set<Long> documentIds) {
        if (documentIds.isEmpty()) {
            // Map.of()はnullキーのgetでNPEを投げるため、HashMapを返す
            // （receipt_document_id=NULLの下書き行からもtoDtoPageが呼ばれる）。
            return new java.util.HashMap<>();
        }
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .in(DocumentVersion::getDocumentId, documentIds)
                        .orderByDesc(DocumentVersion::getVersionNo));
        Map<Long, Integer> latest = new java.util.HashMap<>();
        for (DocumentVersion version : versions) {
            latest.putIfAbsent(version.getDocumentId(), version.getVersionNo());
        }
        return latest;
    }

    private ExpenseRequestDto toDto(ExpenseRequest expense, String engineerName) {
        ApprovalRequest approval = expense.getApprovalRequestId() == null
                ? null : approvalRequestMapper.selectById(expense.getApprovalRequestId());
        return toDto(expense, engineerName, approval,
                latestReceiptVersionNo(expense.getReceiptDocumentId()));
    }

    private ExpenseRequestDto toDto(ExpenseRequest expense, String engineerName, ApprovalRequest approval,
                                    Integer receiptVersionNo) {
        return new ExpenseRequestDto(
                expense.getId(), expense.getExpenseNo(), expense.getExpenseDate(), expense.getCategory(),
                expense.getAmount(), expense.getCustomerId(), expense.getProjectId(), expense.getDescription(),
                expense.getReceiptDocumentId(), receiptVersionNo, expense.getVersion(), expense.getStatus(),
                expense.getApprovalRequestId(),
                approval == null ? null : approval.getStatus(), expense.getPaidAt(),
                expense.getCreatedAt(), expense.getEngineerId(), engineerName);
    }

    private Integer latestReceiptVersionNo(Long documentId) {
        if (documentId == null) {
            return null;
        }
        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .eq(DocumentVersion::getDocumentId, documentId)
                        .orderByDesc(DocumentVersion::getVersionNo)
                        .last("LIMIT 1"));
        return versions.isEmpty() ? null : versions.get(0).getVersionNo();
    }

    private Map<Long, String> engineerNameOf(Set<Long> engineerIds) {
        if (engineerIds.isEmpty()) {
            return new java.util.HashMap<>();
        }
        return engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId,
                        e -> e.getFullName() == null ? "" : e.getFullName()));
    }

    private String engineerNameOf(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        return engineer == null ? null
                : (engineer.getFullName() == null ? "" : engineer.getFullName());
    }

    private void notifyPaid(ExpenseRequest expense) {
        Long userId = applicantUserId(expense);
        if (userId == null) {
            return;
        }
        String expenseNo = expense.getExpenseNo() == null || expense.getExpenseNo().isBlank()
                ? "EX-" + expense.getId() : expense.getExpenseNo();
        String message = "[\"notification.msg.EXPENSE_PAID\", \"" + expenseNo + "\"]";
        notificationService.publishToUser(userId, "EXPENSE_PAID", "経費の支払いを確認しました",
                message, LINK_URL, "expense-paid:" + expense.getId(), LINK_MENU_KEY);
    }

    private Long applicantUserId(ExpenseRequest expense) {
        if (expense == null || expense.getEngineerId() == null) {
            return null;
        }
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(expense.getEngineerId());
        return link == null ? null : link.getSysUserId();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }
}
