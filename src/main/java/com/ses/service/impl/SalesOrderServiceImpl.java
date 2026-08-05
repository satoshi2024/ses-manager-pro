package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.order.SalesOrderDetailDto;
import com.ses.dto.order.SalesOrderListDto;
import com.ses.dto.order.SalesOrderSaveRequest;
import com.ses.entity.Contract;
import com.ses.entity.Quotation;
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.ContractService;
import com.ses.service.SalesOrderService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 注文サービス実装。
 * 状態遷移（design §5.3）と見積→注文→契約の連携の唯一の権威。
 */
@Service
@RequiredArgsConstructor
public class SalesOrderServiceImpl extends ServiceImpl<SalesOrderMapper, SalesOrder>
        implements SalesOrderService {

    /** 状態遷移の唯一の権威（design §5.3の決定表）。フロントJSはこの複製であり、変更時は両方追随する。 */
    private static final Map<String, Set<String>> ALLOWED_STATUS_TRANSITIONS = Map.of(
            StatusConstants.ORDER_DRAFT, Set.of(StatusConstants.ORDER_RECEIVED, StatusConstants.ORDER_CANCELLED),
            StatusConstants.ORDER_RECEIVED, Set.of(StatusConstants.ORDER_ACK_SUBMITTED, StatusConstants.ORDER_CANCELLED),
            StatusConstants.ORDER_ACK_SUBMITTED, Set.of(StatusConstants.ORDER_CONTRACTED, StatusConstants.ORDER_CANCELLED),
            // 契約化→取消は許可遷移だが承認必須（approval経由のみ。直接遷移は changeStatus が拒否する）
            StatusConstants.ORDER_CONTRACTED, Set.of(StatusConstants.ORDER_COMPLETED, StatusConstants.ORDER_CANCELLED),
            StatusConstants.ORDER_COMPLETED, Set.of(),
            StatusConstants.ORDER_CANCELLED, Set.of());

    private final SalesOrderLineMapper lineMapper;
    private final QuotationMapper quotationMapper;
    private final ProjectMapper projectMapper;
    private final EngineerMapper engineerMapper;
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper customerContactMapper;
    private final ContractMapper contractMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final ContractService contractService;
    private final DataScopeService dataScopeService;

    // ===== 一覧・詳細・scope =====

    @Override
    public Page<SalesOrderListDto> pageOrders(long current, long size, String status, String keyword,
                                              LocalDate dateFrom, LocalDate dateTo) {
        List<Long> customerIds = scopedCustomerIds();
        Page<SalesOrderListDto> page = new Page<>(current, Math.min(size, 1000));
        return baseMapper.selectPageWithNames(page, status, keyword, dateFrom, dateTo, customerIds);
    }

    @Override
    public SalesOrderDetailDto detail(Long id) {
        SalesOrder order = require(id);
        assertAllowedOrder(id);
        SalesOrderDetailDto dto = new SalesOrderDetailDto();
        dto.setId(order.getId());
        dto.setOrderNo(order.getOrderNo());
        dto.setCustomerPoNo(order.getCustomerPoNo());
        dto.setCustomerId(order.getCustomerId());
        com.ses.entity.Customer customer = order.getCustomerId() == null ? null
                : customerMapper.selectById(order.getCustomerId());
        dto.setCustomerName(customer == null ? null : customer.getCompanyName());
        dto.setContactId(order.getContactId());
        com.ses.entity.CustomerContact contact = order.getContactId() == null ? null
                : customerContactMapper.selectById(order.getContactId());
        dto.setContactName(contact == null ? null : contact.getName());
        dto.setQuotationId(order.getQuotationId());
        Quotation quotation = order.getQuotationId() == null ? null : quotationMapper.selectById(order.getQuotationId());
        dto.setQuotationNo(quotation == null ? null : quotation.getQuotationNo());
        dto.setOrderDate(order.getOrderDate());
        dto.setStartDate(order.getStartDate());
        dto.setEndDate(order.getEndDate());
        dto.setStatus(order.getStatus());
        dto.setTotalAmountSnapshot(order.getTotalAmountSnapshot());
        dto.setPaymentTermsSnapshot(order.getPaymentTermsSnapshot());
        dto.setSourceDocumentId(order.getSourceDocumentId());
        dto.setAcknowledgementDocumentId(order.getAcknowledgementDocumentId());
        dto.setVersion(order.getVersion());
        dto.setLines(loadLines(order.getId()));
        dto.setDiffs(computeDiffs(order));
        return dto;
    }

    private List<SalesOrderDetailDto.Line> loadLines(Long orderId) {
        List<SalesOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<SalesOrderLine>()
                .eq(SalesOrderLine::getOrderId, orderId)
                .orderByAsc(SalesOrderLine::getLineNo));
        List<SalesOrderDetailDto.Line> result = new ArrayList<>();
        for (SalesOrderLine line : lines) {
            SalesOrderDetailDto.Line dto = new SalesOrderDetailDto.Line();
            dto.setId(line.getId());
            dto.setLineNo(line.getLineNo());
            dto.setProjectId(line.getProjectId());
            com.ses.entity.Project project = line.getProjectId() == null ? null
                    : projectMapper.selectById(line.getProjectId());
            dto.setProjectName(project == null ? null : project.getProjectName());
            dto.setEngineerId(line.getEngineerId());
            com.ses.entity.Engineer engineer = engineerMapper.selectById(line.getEngineerId());
            dto.setEngineerName(engineer == null ? null : engineer.getFullName());
            dto.setQuantity(line.getQuantity());
            dto.setUnitPrice(line.getUnitPrice());
            dto.setSettlementMin(line.getSettlementMin());
            dto.setSettlementMax(line.getSettlementMax());
            dto.setAmount(line.getAmount());
            dto.setRemarks(line.getRemarks());
            Contract contract = contractMapper.selectOne(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getOrderLineId, line.getId()).last("LIMIT 1"));
            if (contract != null) {
                dto.setContractId(contract.getId());
                dto.setContractNo(contract.getContractNo());
            }
            result.add(dto);
        }
        return result;
    }

    @Override
    public void assertAllowedOrder(Long orderId) {
        SalesOrder order = require(orderId);
        if (dataScopeService.isScoped()
                && !dataScopeService.allowedCustomerIds().contains(order.getCustomerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
    }

    // ===== 作成・更新・削除 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrder createFromRequest(SalesOrderSaveRequest request) {
        if (request.getCustomerId() == null) {
            throw BusinessException.of("error.order.customerRequired");
        }
        dataScopeService.assertAllowedCustomer(request.getCustomerId());
        validateRequest(request);
        SalesOrder order = new SalesOrder();
        order.setCustomerId(request.getCustomerId());
        order.setContactId(request.getContactId());
        order.setQuotationId(request.getQuotationId());
        order.setCustomerPoNo(normalizePo(request.getCustomerPoNo()));
        order.setOrderDate(request.getOrderDate());
        order.setStartDate(request.getStartDate());
        order.setEndDate(request.getEndDate());
        order.setStatus(StatusConstants.ORDER_DRAFT);
        // 下書きの間は支払条件snapshotに編集値を保持（受領確認で固定）。未入力はNULL=未確定。
        order.setPaymentTermsSnapshot(trimToNull(request.getPaymentTerms()));
        insertWithNoRetry(order);
        insertLines(order.getId(), request.getLines());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrder updateFromRequest(Long id, SalesOrderSaveRequest request) {
        SalesOrder order = require(id);
        assertAllowedOrder(id);
        if (!StatusConstants.ORDER_DRAFT.equals(order.getStatus())) {
            throw BusinessException.of(409, "error.order.notEditable", order.getStatus());
        }
        if (request.getCustomerId() != null && !Objects.equals(order.getCustomerId(), request.getCustomerId())) {
            dataScopeService.assertAllowedCustomer(request.getCustomerId());
        }
        validateRequest(request);
        order.setCustomerId(request.getCustomerId() != null ? request.getCustomerId() : order.getCustomerId());
        order.setContactId(request.getContactId());
        order.setQuotationId(request.getQuotationId());
        order.setCustomerPoNo(normalizePo(request.getCustomerPoNo()));
        order.setOrderDate(request.getOrderDate());
        order.setStartDate(request.getStartDate());
        order.setEndDate(request.getEndDate());
        order.setPaymentTermsSnapshot(trimToNull(request.getPaymentTerms()));
        this.baseMapper.updateById(order);
        // 下書き差替は物理削除→再作成（論理削除だと (order_id, line_no) UNIQUEと衝突する）
        lineMapper.deletePhysicalByOrderId(order.getId());
        insertLines(order.getId(), request.getLines());
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrder(Long id) {
        SalesOrder order = require(id);
        assertAllowedOrder(id);
        if (!StatusConstants.ORDER_DRAFT.equals(order.getStatus())) {
            throw BusinessException.of(409, "error.order.deleteNotAllowed", order.getStatus());
        }
        lineMapper.deletePhysicalByOrderId(id);
        this.baseMapper.deleteById(id);
    }

    @Override
    public boolean isCustomerPoDuplicate(Long customerId, String customerPoNo) {
        String po = normalizePo(customerPoNo);
        if (po == null) {
            return false;
        }
        return this.baseMapper.selectCount(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getCustomerId, customerId)
                .eq(SalesOrder::getCustomerPoNo, po)) > 0;
    }

    // ===== 状態機械 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrder changeStatus(Long id, String newStatus) {
        SalesOrder order = baseMapper.selectByIdForUpdate(id);
        if (order == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        assertAllowedOrder(id);
        Set<String> allowed = ALLOWED_STATUS_TRANSITIONS.getOrDefault(order.getStatus(), Set.of());
        if (newStatus == null || !allowed.contains(newStatus)) {
            throw BusinessException.of(409, "error.order.statusTransitionInvalid", order.getStatus(), newStatus);
        }
        if (StatusConstants.ORDER_CANCELLED.equals(newStatus)
                && StatusConstants.ORDER_CONTRACTED.equals(order.getStatus())) {
            // 契約化→取消は承認必須（design §5.3）。直接遷移は拒否し承認APIへ誘導する。
            throw BusinessException.of(400, "error.order.cancelRequiresApproval");
        }
        if (StatusConstants.ORDER_RECEIVED.equals(newStatus)) {
            // 注文確定時点で金額・支払条件snapshotを固定する（design §5.1）。
            order.setTotalAmountSnapshot(calcTotal(order.getId()));
        }
        order.setStatus(newStatus);
        this.baseMapper.updateById(order);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyCancellation(Long id) {
        SalesOrder order = baseMapper.selectByIdForUpdate(id);
        if (order == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (!StatusConstants.ORDER_CONTRACTED.equals(order.getStatus())
                && !StatusConstants.ORDER_RECEIVED.equals(order.getStatus())
                && !StatusConstants.ORDER_ACK_SUBMITTED.equals(order.getStatus())) {
            throw BusinessException.of(409, "error.order.statusTransitionInvalid",
                    order.getStatus(), StatusConstants.ORDER_CANCELLED);
        }
        order.setStatus(StatusConstants.ORDER_CANCELLED);
        this.baseMapper.updateById(order);
    }

    // ===== 採番 =====

    @Override
    public String generateOrderNo(LocalDate baseDate) {
        String prefix = "O-" + baseDate.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        String maxNo = this.baseMapper.selectMaxOrderNo(prefix);
        if (maxNo == null) {
            return prefix + "0001";
        }
        String seqStr = maxNo.substring(prefix.length());
        int nextSeq = Integer.parseInt(seqStr) + 1;
        return prefix + String.format("%04d", nextSeq);
    }

    // ===== 見積→注文→契約 =====

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SalesOrder createDraftFromQuotation(Long quotationId) {
        Quotation quotation = quotationMapper.selectById(quotationId);
        if (quotation == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedCustomer(quotation.getCustomerId());
        // 冪等: 同一見積から生成済みの注文があればそれを返す。
        SalesOrder existing = this.baseMapper.selectOne(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getQuotationId, quotationId).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        if (quotation.getEngineerId() == null) {
            throw BusinessException.of(400, "error.order.quotationEngineerRequired");
        }
        SalesOrder order = new SalesOrder();
        order.setCustomerId(quotation.getCustomerId());
        order.setQuotationId(quotation.getId());
        order.setOrderDate(LocalDate.now());
        order.setStartDate(quotation.getValidUntil());
        order.setStatus(StatusConstants.ORDER_DRAFT);
        insertWithNoRetry(order);

        SalesOrderLine line = new SalesOrderLine();
        line.setOrderId(order.getId());
        line.setLineNo(1);
        line.setProjectId(quotation.getProjectId());
        line.setEngineerId(quotation.getEngineerId());
        line.setQuantity(1);
        line.setUnitPrice(quotation.getUnitPrice());
        line.setSettlementMin(quotation.getSettlementHoursMin());
        line.setSettlementMax(quotation.getSettlementHoursMax());
        line.setAmount(quotation.getUnitPrice());
        lineMapper.insert(line);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Contract> createContractDrafts(Long orderId) {
        SalesOrder order = require(orderId);
        assertAllowedOrder(orderId);
        if (!StatusConstants.ORDER_ACK_SUBMITTED.equals(order.getStatus())
                && !StatusConstants.ORDER_CONTRACTED.equals(order.getStatus())) {
            throw BusinessException.of(409, "error.order.contractNotAllowed", order.getStatus());
        }
        // 条件差分があれば承認済みでない限り契約化できない（R2.3）。
        List<SalesOrderDetailDto.DiffItem> diffs = computeDiffs(order);
        if (!diffs.isEmpty() && !hasApprovedConditionDiff(orderId)) {
            throw BusinessException.of(409, "error.order.conditionDiffApprovalRequired");
        }
        List<SalesOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<SalesOrderLine>()
                .eq(SalesOrderLine::getOrderId, orderId)
                .orderByAsc(SalesOrderLine::getLineNo));
        List<Contract> contracts = new ArrayList<>();
        for (SalesOrderLine line : lines) {
            Contract existing = contractMapper.selectOne(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getOrderLineId, line.getId()).last("LIMIT 1"));
            if (existing != null) {
                contracts.add(existing); // 冪等: 1明細→1契約（order_line_id UNIQUE）
                continue;
            }
            contracts.add(contractService.createDraftFromSalesOrderLine(line, order));
        }
        boolean allDone = lines.stream().allMatch(l -> contractMapper.selectCount(
                new LambdaQueryWrapper<Contract>().eq(Contract::getOrderLineId, l.getId())) > 0);
        if (allDone && !StatusConstants.ORDER_CONTRACTED.equals(order.getStatus())) {
            changeStatus(orderId, StatusConstants.ORDER_CONTRACTED);
        }
        return contracts;
    }

    /** 注文条件と見積/契約の差分（R2.3）。承認対象判定にも使う。 */
    @Override
    public List<SalesOrderDetailDto.DiffItem> computeDiffs(SalesOrder order) {
        List<SalesOrderDetailDto.DiffItem> diffs = new ArrayList<>();
        Quotation quotation = order.getQuotationId() == null ? null
                : quotationMapper.selectById(order.getQuotationId());
        List<SalesOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<SalesOrderLine>()
                .eq(SalesOrderLine::getOrderId, order.getId())
                .orderByAsc(SalesOrderLine::getLineNo));
        for (SalesOrderLine line : lines) {
            String engineerName = engineerName(line.getEngineerId());
            if (quotation != null) {
                addDiff(diffs, "unitPrice", "単価", engineerName,
                        quotation.getUnitPrice(), line.getUnitPrice(), "QUOTATION");
                addDiff(diffs, "settlementMin", "精算下限", engineerName,
                        quotation.getSettlementHoursMin(), line.getSettlementMin(), "QUOTATION");
                addDiff(diffs, "settlementMax", "精算上限", engineerName,
                        quotation.getSettlementHoursMax(), line.getSettlementMax(), "QUOTATION");
            }
            Contract contract = contractMapper.selectOne(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getOrderLineId, line.getId()).last("LIMIT 1"));
            if (contract != null) {
                addDiff(diffs, "unitPrice", "単価", engineerName,
                        contract.getSellingPrice(), line.getUnitPrice(), "CONTRACT");
                addDiff(diffs, "settlementMin", "精算下限", engineerName,
                        contract.getSettlementHoursMin(), line.getSettlementMin(), "CONTRACT");
                addDiff(diffs, "settlementMax", "精算上限", engineerName,
                        contract.getSettlementHoursMax(), line.getSettlementMax(), "CONTRACT");
            }
        }
        return diffs;
    }

    private boolean hasApprovedConditionDiff(Long orderId) {
        return approvalRequestMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.ApprovalRequest>()
                        .eq("request_type", "order.conditionDiff")
                        .eq("target_type", "SALES_ORDER")
                        .eq("target_id", orderId)
                        .eq("status", "APPROVED")) > 0;
    }

    // ===== 内部ヘルパー =====

    private void insertWithNoRetry(SalesOrder order) {
        final int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            order.setOrderNo(generateOrderNo(LocalDate.now()));
            try {
                this.baseMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                // 同時採番の衝突。次のループで最新の最大値から再採番する
            }
        }
        throw BusinessException.of("error.order.numberGenerateFailed");
    }

    private void insertLines(Long orderId, List<SalesOrderSaveRequest.Line> lines) {
        int lineNo = 1;
        for (SalesOrderSaveRequest.Line req : lines) {
            SalesOrderLine line = new SalesOrderLine();
            line.setOrderId(orderId);
            line.setLineNo(lineNo++);
            line.setProjectId(req.getProjectId());
            line.setEngineerId(req.getEngineerId());
            line.setQuantity(1);
            line.setUnitPrice(req.getUnitPrice());
            line.setSettlementMin(req.getSettlementMin());
            line.setSettlementMax(req.getSettlementMax());
            line.setAmount(req.getUnitPrice());
            line.setRemarks(trimToNull(req.getRemarks()));
            lineMapper.insert(line);
        }
    }

    private BigDecimal calcTotal(Long orderId) {
        List<SalesOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<SalesOrderLine>()
                .eq(SalesOrderLine::getOrderId, orderId));
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            BigDecimal amount = line.getAmount() != null ? line.getAmount() : BigDecimal.ZERO;
            total = total.add(amount);
        }
        return total;
    }

    private List<Long> scopedCustomerIds() {
        if (!dataScopeService.isScoped()) {
            return null; // 全件（SQL側で条件を付けない）
        }
        Set<Long> ids = dataScopeService.allowedCustomerIds();
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    private void validateRequest(SalesOrderSaveRequest request) {
        if (request.getOrderDate() == null) {
            throw BusinessException.of("error.order.orderDateRequired");
        }
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw BusinessException.of("error.order.lineRequired");
        }
        for (SalesOrderSaveRequest.Line line : request.getLines()) {
            if (line.getEngineerId() == null) {
                throw BusinessException.of("error.order.engineerRequired");
            }
            if (line.getUnitPrice() == null) {
                throw BusinessException.of("error.order.unitPriceRequired");
            }
            if (line.getSettlementMin() != null && line.getSettlementMax() != null
                    && line.getSettlementMin().compareTo(line.getSettlementMax()) > 0) {
                throw BusinessException.of("error.order.settlementRangeInvalid");
            }
        }
        if (request.getEndDate() != null && request.getStartDate() != null
                && request.getEndDate().isBefore(request.getStartDate())) {
            throw BusinessException.of("error.order.dateRangeInvalid");
        }
    }

    private SalesOrder require(Long id) {
        SalesOrder order = id == null ? null : this.baseMapper.selectById(id);
        if (order == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return order;
    }

    @Override
    public String normalizePo(String po) {
        return trimToNull(po);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void addDiff(List<SalesOrderDetailDto.DiffItem> diffs, String field, String label,
                         String engineerName, BigDecimal before, BigDecimal after, String target) {
        if (!Objects.equals(before, after)) {
            SalesOrderDetailDto.DiffItem item = new SalesOrderDetailDto.DiffItem();
            item.setField(field);
            item.setLabel(label + "（" + engineerName + "）");
            item.setBefore(before == null ? "未設定" : before.toPlainString());
            item.setAfter(after == null ? "未設定" : after.toPlainString());
            item.setTarget(target);
            diffs.add(item);
        }
    }

    private String engineerName(Long engineerId) {
        com.ses.entity.Engineer engineer = engineerId == null ? null : engineerMapper.selectById(engineerId);
        return engineer == null ? String.valueOf(engineerId) : engineer.getFullName();
    }
}
