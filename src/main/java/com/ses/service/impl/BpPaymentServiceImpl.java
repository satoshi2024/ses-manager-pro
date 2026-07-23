package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.BpPaymentService;
import com.ses.service.MonthlyClosingService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BpPaymentServiceImpl implements BpPaymentService {

    private final BpPaymentMapper bpPaymentMapper;
    private final WorkRecordMapper workRecordMapper;
    private final MonthlyClosingService monthlyClosingService;

    @Override
    public List<BpPaymentTreeDto> getTreeByWorkRecordId(Long workRecordId) {
        List<BpPayment> payments = bpPaymentMapper.selectByWorkRecordIdOrderByLayer(workRecordId);
        if (payments == null || payments.isEmpty()) {
            return new ArrayList<>();
        }

        List<BpPaymentTreeDto> dtos = payments.stream().map(p -> {
            BpPaymentTreeDto dto = new BpPaymentTreeDto();
            BeanUtils.copyProperties(p, dto);
            dto.setChildren(new ArrayList<>());
            return dto;
        }).collect(Collectors.toList());

        Map<Long, BpPaymentTreeDto> dtoMap = dtos.stream().collect(Collectors.toMap(BpPaymentTreeDto::getId, d -> d));
        List<BpPaymentTreeDto> rootNodes = new ArrayList<>();

        for (BpPaymentTreeDto dto : dtos) {
            if (dto.getParentPaymentId() == null) {
                rootNodes.add(dto);
            } else {
                BpPaymentTreeDto parent = dtoMap.get(dto.getParentPaymentId());
                if (parent != null) {
                    parent.getChildren().add(dto);
                } else {
                    rootNodes.add(dto);
                }
            }
        }

        for (BpPaymentTreeDto root : rootNodes) {
            calculateMarginRecursive(root);
        }

        return rootNodes;
    }

    private void calculateMarginRecursive(BpPaymentTreeDto node) {
        BigDecimal childrenTotal = BigDecimal.ZERO;
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            for (BpPaymentTreeDto child : node.getChildren()) {
                calculateMarginRecursive(child);
                if (child.getAmount() != null) {
                    childrenTotal = childrenTotal.add(child.getAmount());
                }
            }
        }
        if (node.getAmount() != null) {
            node.setMargin(node.getAmount().subtract(childrenTotal));
        } else {
            node.setMargin(BigDecimal.ZERO);
        }
    }

    @Override
    @Transactional
    public BpPayment addLayer(BpPayment bpPayment) {
        if (bpPayment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(bpPayment.getWorkRecordId());
            if (wr != null) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth());
            }
        }

        Long count = bpPaymentMapper.selectCount(new LambdaQueryWrapper<BpPayment>()
                .eq(BpPayment::getWorkRecordId, bpPayment.getWorkRecordId())
                .eq(BpPayment::getLayerOrder, bpPayment.getLayerOrder()));
        if (count > 0) {
            throw BusinessException.of("error.bpPayment.duplicateLayer");
        }

        if (bpPayment.getParentPaymentId() != null) {
            BpPayment parent = bpPaymentMapper.selectById(bpPayment.getParentPaymentId());
            if (parent == null || !parent.getWorkRecordId().equals(bpPayment.getWorkRecordId())) {
                throw BusinessException.of("error.bpPayment.parentInvalid");
            }
            if (parent.getLayerOrder() >= bpPayment.getLayerOrder()) {
                throw BusinessException.of("error.bpPayment.parentOrderInvalid");
            }
        }

        if (bpPayment.getStatus() == null) {
            bpPayment.setStatus("未払");
        }
        try {
            bpPaymentMapper.insert(bpPayment);
        } catch (DuplicateKeyException ex) {
            throw BusinessException.of("error.bpPayment.duplicateLayer");
        }
        return bpPayment;
    }

    @Override
    @Transactional
    public BpPayment updateLayer(Long id, BpPayment bpPayment) {
        BpPayment existing = bpPaymentMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of("error.bpPayment.notFound");
        }
        if (existing.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(existing.getWorkRecordId());
            if (wr != null) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth());
            }
        }
        if ((bpPayment.getStatus() != null && !Objects.equals(existing.getStatus(), bpPayment.getStatus()))
                || (bpPayment.getPaidDate() != null && !Objects.equals(existing.getPaidDate(), bpPayment.getPaidDate()))) {
            throw BusinessException.of("error.bpPayment.statusDedicatedApi");
        }
        if ("支払済".equals(existing.getStatus())
                && bpPayment.getAmount() != null
                && existing.getAmount() != null
                && existing.getAmount().compareTo(bpPayment.getAmount()) != 0) {
            throw BusinessException.of("error.bpPayment.paidAmountEdit");
        }

        if (bpPayment.getAmount() != null) {
            existing.setAmount(bpPayment.getAmount());
        }
        existing.setRemarks(bpPayment.getRemarks());
        UpdateWrapper<BpPayment> update = new UpdateWrapper<BpPayment>()
                .eq("id", id)
                .eq("status", existing.getStatus())
                .set(bpPayment.getAmount() != null, "amount", bpPayment.getAmount())
                .set(bpPayment.getRemarks() != null, "remarks", bpPayment.getRemarks());
        int updated = bpPaymentMapper.update(null, update);
        if (updated == 0) {
            throw BusinessException.of("error.common.optimisticLock");
        }
        return existing;
    }

    @Override
    @Transactional
    public void deleteLayer(Long id) {
        BpPayment existing = bpPaymentMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of("error.bpPayment.notFound");
        }
        if (existing.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(existing.getWorkRecordId());
            if (wr != null) {
                monthlyClosingService.assertOpenForUpdate(wr.getWorkMonth());
            }
        }
        if ("支払済".equals(existing.getStatus())) {
            throw BusinessException.of("error.bpPayment.paidDelete");
        }
        Long childCount = bpPaymentMapper.selectCount(new LambdaQueryWrapper<BpPayment>()
                .eq(BpPayment::getParentPaymentId, id));
        if (childCount > 0) {
            throw BusinessException.of("error.bpPayment.hasChildren");
        }
        bpPaymentMapper.deleteById(id);
    }
}
