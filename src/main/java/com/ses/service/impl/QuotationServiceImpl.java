package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.ContractService;
import com.ses.service.QuotationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 見積サービス実装。
 */
@Service
public class QuotationServiceImpl extends ServiceImpl<QuotationMapper, Quotation> implements QuotationService {

    // 状態遷移の唯一の権威。フロントの STATUS_TRANSITIONS(quotation.js)はこの複製であり、変更時は両方追随すること。
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "下書き", Set.of("提出済"),
            "提出済", Set.of("受注", "失注"),
            "受注", Set.of(),
            "失注", Set.of());

    // 受注/失注後は編集不可（備考のみ許可）。
    private static final Set<String> CLOSED = Set.of("受注", "失注");

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ProjectMapper projectMapper;

    // ContractServiceImpl も QuotationMapperではないがドラフトはContract側で生成。循環回避のため Lazy。
    @Autowired
    @Lazy
    private ContractService contractService;

    @Override
    public String generateQuotationNo(LocalDate baseDate) {
        String monthStr = baseDate.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String prefix = "Q-" + monthStr + "-";
        String maxNo = baseMapper.selectMaxQuotationNoIncludingDeleted(prefix);
        if (maxNo == null) {
            return prefix + "0001";
        }
        int seq = Integer.parseInt(maxNo.substring(prefix.length()));
        return String.format("%s%04d", prefix, seq + 1);
    }

    private void validate(Quotation q) {
        Customer customer = customerMapper.selectById(q.getCustomerId());
        if (customer == null) {
            throw BusinessException.of("error.quotation.customerNotFound");
        }
        if (q.getProjectId() != null) {
            Project project = projectMapper.selectById(q.getProjectId());
            if (project == null) {
                throw BusinessException.of("error.quotation.projectNotFound");
            }
            if (!q.getCustomerId().equals(project.getCustomerId())) {
                throw BusinessException.of("error.quotation.projectCustomerMismatch");
            }
        }
        if (q.getUnitPrice() == null || q.getUnitPrice().signum() < 0) {
            throw BusinessException.of("error.quotation.unitPriceInvalid");
        }
        if (q.getSettlementHoursMin() != null && q.getSettlementHoursMax() != null
                && q.getSettlementHoursMin().compareTo(q.getSettlementHoursMax()) > 0) {
            throw BusinessException.of("error.quotation.settlementRangeInvalid");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveWithBusinessRules(Quotation q) {
        validate(q);
        // 全登録経路を下書き開始に固定する（内部経路からの受注等の状態注入を防ぐ / R3R-26）。
        q.setStatus("下書き");
        LocalDate baseDate = LocalDate.now();
        final int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            q.setQuotationNo(generateQuotationNo(baseDate));
            try {
                baseMapper.insert(q);
                return;
            } catch (DuplicateKeyException e) {
                // 同時採番の衝突。次のループで最新の最大値から再採番する。
            }
        }
        throw BusinessException.of("error.quotation.numberGenerateFailed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateWithBusinessRules(Quotation q) {
        // 行ロックで状態遷移と直列化し、終端化後に金額等を上書きされないようにする（R3R-25）。
        Quotation current = this.baseMapper.selectByIdForUpdate(q.getId());
        if (current == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        // 受注/失注後は通常updateを拒否する（備考追記は専用APIへ / R3R-24）。
        if (CLOSED.contains(current.getStatus())) {
            throw BusinessException.of(400, "error.quotation.terminalUpdate");
        }
        validate(q);
        // 採番・ステータスは更新経路では変更しない。
        q.setQuotationNo(null);
        q.setStatus(null);
        this.updateById(q);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, String newStatus) {
        // 行ロックで並行遷移を直列化する（提出済→受注/失注の同時要求を片方のみ成功させる / R3R-25）。
        Quotation q = this.baseMapper.selectByIdForUpdate(id);
        if (q == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        if (!ALLOWED.getOrDefault(q.getStatus(), Set.of()).contains(newStatus)) {
            throw BusinessException.of("error.quotation.statusTransitionInvalid", q.getStatus(), newStatus);
        }
        if ("受注".equals(newStatus) && q.getEngineerId() == null) {
            throw BusinessException.of(409, "error.quotation.engineerRequired");
        }
        q.setStatus(newStatus);
        this.updateById(q);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendRemark(Long id, String additional) {
        // 行ロック内で最新値へ追記し、並行追記の後勝ちによる履歴喪失を防ぐ（R3R-24）。
        Quotation q = this.baseMapper.selectByIdForUpdate(id);
        if (q == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        String current = q.getRemarks();
        String merged = (current == null || current.isEmpty())
                ? additional : current + "\n" + additional;
        Quotation upd = new Quotation();
        upd.setId(id);
        upd.setRemarks(merged);
        this.updateById(upd);
    }

    @Override
    public boolean removeById(Serializable id) {
        Quotation q = this.getById(id);
        if (q != null && !"下書き".equals(q.getStatus())) {
            throw BusinessException.of("error.quotation.deleteNonDraft");
        }
        return super.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Contract createDraftFromQuotation(Long quotationId) {
        Quotation q = this.baseMapper.selectByIdForUpdate(quotationId);
        if (q == null) {
            throw BusinessException.of("error.quotation.notFound");
        }
        if (!"受注".equals(q.getStatus())) {
            throw BusinessException.of(409, "error.quotation.notAccepted");
        }
        return contractService.createDraftFromQuotation(q);
    }
}
