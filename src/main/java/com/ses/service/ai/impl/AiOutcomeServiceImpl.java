package com.ses.service.ai.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.AiOutcome;
import com.ses.entity.Contract;
import com.ses.entity.Opportunity;
import com.ses.entity.Proposal;
import com.ses.mapper.AiOutcomeMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.ai.AiOutcomeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiOutcomeServiceImpl implements AiOutcomeService {

    private static final Set<String> INTERVIEW_STATUSES = Set.of("一次面接", "二次面接", "結果待ち");

    private final AiOutcomeMapper outcomeMapper;
    private final ProposalMapper proposalMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onProposalSaved(Proposal proposal) {
        if (proposal == null || proposal.getAiItemId() == null || proposal.getId() == null) {
            return;
        }
        insert(proposal.getAiItemId(), "PROPOSAL_CREATED", "PROPOSAL", proposal.getId(),
                LocalDateTime.now(), null, null);
        if (proposal.getPositionId() != null) {
            insert(proposal.getAiItemId(), "POSITION_LINKED", "PROPOSAL", proposal.getId(),
                    LocalDateTime.now(), null, "{\"positionId\":" + proposal.getPositionId() + "}");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onProposalStatusChanged(Proposal proposal) {
        if (proposal == null || proposal.getAiItemId() == null || proposal.getId() == null) {
            return;
        }
        String status = proposal.getStatus();
        LocalDateTime now = LocalDateTime.now();
        if (INTERVIEW_STATUSES.contains(status)) {
            insert(proposal.getAiItemId(), "INTERVIEW", "PROPOSAL", proposal.getId(), now, null, null);
        } else if ("成約".equals(status)) {
            insert(proposal.getAiItemId(), "WIN", "PROPOSAL", proposal.getId(), now, null, null);
        } else if ("見送り".equals(status)) {
            insert(proposal.getAiItemId(), "LOSS", "PROPOSAL", proposal.getId(), now, null, null);
        }
        if (proposal.getPositionId() != null) {
            insert(proposal.getAiItemId(), "POSITION_LINKED", "PROPOSAL", proposal.getId(),
                    now, null, "{\"positionId\":" + proposal.getPositionId() + "}");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onOpportunityStageChanged(Opportunity opportunity) {
        if (opportunity == null || opportunity.getId() == null) {
            return;
        }
        List<Proposal> linked = proposalMapper.selectList(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getSourceOpportunityId, opportunity.getId())
                .isNotNull(Proposal::getAiItemId));
        if (linked.isEmpty()) {
            return;
        }
        String type = null;
        if ("受注".equals(opportunity.getStage())) {
            type = "WIN";
        } else if ("失注".equals(opportunity.getStage())) {
            type = "LOSS";
        }
        if (type == null) {
            return;
        }
        String value = "LOSS".equals(type) ? "{\"reasonCode\":\"OTHER_REDACTED\"}" : null;
        LocalDateTime now = LocalDateTime.now();
        for (Proposal proposal : linked) {
            insert(proposal.getAiItemId(), type, "OPPORTUNITY", opportunity.getId(), now, null, value);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onContractCancelled(Contract contract, LocalDate originalEndDate, LocalDate cancelDate) {
        Long itemId = itemIdOf(contract);
        if (itemId == null || contract == null || contract.getId() == null) {
            return;
        }
        recordEarlyExit(itemId, contract.getId(), originalEndDate, cancelDate);
    }

    public void recordEarlyExit(Long itemId, Long contractId, LocalDate originalEndDate, LocalDate cancelDate) {
        if (itemId == null || contractId == null) {
            return;
        }
        if (isEarlyExit(originalEndDate, cancelDate)) {
            insert(itemId, "EARLY_EXIT", "CONTRACT", contractId,
                    LocalDateTime.now(), originalEndDate, null);
        }
    }

    public static boolean isEarlyExit(LocalDate originalEndDate, LocalDate cancelDate) {
        return originalEndDate != null && cancelDate != null && cancelDate.isBefore(originalEndDate);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onContractRenewalContinued(Contract contract) {
        Long itemId = itemIdOf(contract);
        if (itemId == null || contract == null || contract.getId() == null) {
            return;
        }
        insert(itemId, "CONTRACT_CONTINUED", "CONTRACT", contract.getId(),
                LocalDateTime.now(), null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onContractPositionLinked(Contract contract) {
        Long itemId = itemIdOf(contract);
        if (itemId == null || contract == null || contract.getId() == null || contract.getPositionId() == null) {
            return;
        }
        insert(itemId, "POSITION_LINKED", "CONTRACT", contract.getId(),
                LocalDateTime.now(), null, "{\"positionId\":" + contract.getPositionId() + "}");
    }

    private Long itemIdOf(Contract contract) {
        if (contract == null || contract.getProposalId() == null) {
            return null;
        }
        Proposal proposal = proposalMapper.selectById(contract.getProposalId());
        return proposal == null ? null : proposal.getAiItemId();
    }

    private void insert(Long itemId, String outcomeType, String sourceType, Long sourceId,
                        LocalDateTime occurredAt, LocalDate originalEnd, String valueJson) {
        AiOutcome outcome = new AiOutcome();
        outcome.setItemId(itemId);
        outcome.setOutcomeType(outcomeType);
        outcome.setSourceType(sourceType);
        outcome.setSourceId(sourceId);
        outcome.setOccurredAt(occurredAt);
        outcome.setOriginalEndDate(originalEnd);
        outcome.setValueJson(valueJson);
        try {
            outcomeMapper.insert(outcome);
        } catch (DataIntegrityViolationException ignored) {
            // 冪等: UNIQUE(item_id, outcome_type, source_type, source_id)
        } catch (Exception ex) {
            log.warn("AI outcome の登録をスキップしました: {}", ex.getMessage());
        }
    }
}
