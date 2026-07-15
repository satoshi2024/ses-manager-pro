package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.BpPaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BpPaymentServiceImpl implements BpPaymentService {

    private final BpPaymentMapper bpPaymentMapper;

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
        Long count = bpPaymentMapper.selectCount(new LambdaQueryWrapper<BpPayment>()
                .eq(BpPayment::getWorkRecordId, bpPayment.getWorkRecordId())
                .eq(BpPayment::getLayerOrder, bpPayment.getLayerOrder()));
        if (count > 0) {
            throw new IllegalArgumentException("指定された階層は既に存在します。");
        }

        if (bpPayment.getParentPaymentId() != null) {
            BpPayment parent = bpPaymentMapper.selectById(bpPayment.getParentPaymentId());
            if (parent == null || !parent.getWorkRecordId().equals(bpPayment.getWorkRecordId())) {
                throw new IllegalArgumentException("親階層が正しくありません。");
            }
            if (parent.getLayerOrder() >= bpPayment.getLayerOrder()) {
                throw new IllegalArgumentException("親階層は自身より上位（小さい番号）である必要があります。");
            }
        }

        bpPaymentMapper.insert(bpPayment);
        return bpPayment;
    }

    @Override
    @Transactional
    public BpPayment updateLayer(Long id, BpPayment bpPayment) {
        BpPayment existing = bpPaymentMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("対象データが見つかりません。");
        }
        existing.setAmount(bpPayment.getAmount());
        existing.setStatus(bpPayment.getStatus());
        existing.setPaidDate(bpPayment.getPaidDate());
        existing.setRemarks(bpPayment.getRemarks());
        bpPaymentMapper.updateById(existing);
        return existing;
    }

    @Override
    @Transactional
    public void deleteLayer(Long id) {
        Long childCount = bpPaymentMapper.selectCount(new LambdaQueryWrapper<BpPayment>()
                .eq(BpPayment::getParentPaymentId, id));
        if (childCount > 0) {
            throw new IllegalArgumentException("子階層が存在するため削除できません。先に子階層を削除してください。");
        }
        bpPaymentMapper.deleteById(id);
    }
}
