package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.engineersales.SalesUserOptionDto;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 要員担当営業サービス実装
 */
@Service
@RequiredArgsConstructor
public class EngineerSalesServiceImpl extends ServiceImpl<EngineerSalesMapper, EngineerSales>
        implements EngineerSalesService {

    private final SysUserMapper sysUserMapper;

    @Override
    public List<EngineerSalesDto> listActive(Long engineerId) {
        return baseMapper.selectActiveWithNames(engineerId);
    }

    @Override
    public List<EngineerSalesDto> listHistory(Long engineerId) {
        return baseMapper.selectHistoryWithNames(engineerId);
    }

    @Override
    @Transactional
    public void assign(Long engineerId, Long salesUserId, boolean primaryFlag, String remarks) {
        SysUser user = sysUserMapper.selectById(salesUserId);
        if (user == null || !StatusConstants.ROLE_SALES.equals(user.getRole())
                || user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.of("error.engineerSales.notSalesRole");
        }

        List<EngineerSales> actives = listActiveEntities(engineerId);
        boolean duplicated = actives.stream().anyMatch(es -> es.getSalesUserId().equals(salesUserId));
        if (duplicated) {
            throw BusinessException.of("error.engineerSales.duplicate");
        }

        // 最初の割当は自動的に主担当。主担当指定時は既存主担当を降格する
        boolean toPrimary = primaryFlag || actives.isEmpty();
        if (primaryFlag) {
            demoteCurrentPrimary(actives);
        }

        EngineerSales entity = EngineerSales.builder()
                .engineerId(engineerId)
                .salesUserId(salesUserId)
                .primaryFlag(toPrimary ? 1 : 0)
                .assignedAt(LocalDate.now())
                .remarks(remarks)
                .build();
        save(entity);
    }

    @Override
    @Transactional
    public void setPrimary(Long engineerId, Long assignmentId) {
        EngineerSales target = getActiveAssignment(engineerId, assignmentId);
        if (target.getPrimaryFlag() != null && target.getPrimaryFlag() == 1) {
            return;
        }
        demoteCurrentPrimary(listActiveEntities(engineerId));
        target.setPrimaryFlag(1);
        updateById(target);
    }

    @Override
    @Transactional
    public void release(Long engineerId, Long assignmentId) {
        EngineerSales target = getActiveAssignment(engineerId, assignmentId);
        List<EngineerSales> actives = listActiveEntities(engineerId);
        boolean isPrimary = target.getPrimaryFlag() != null && target.getPrimaryFlag() == 1;
        if (isPrimary && actives.size() > 1) {
            throw BusinessException.of("error.engineerSales.primaryReleaseBlocked");
        }
        target.setReleasedAt(LocalDate.now());
        updateById(target);
    }

    @Override
    public Long findPrimarySalesUserId(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        EngineerSales primary = getOne(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, engineerId)
                .isNull(EngineerSales::getReleasedAt)
                .eq(EngineerSales::getPrimaryFlag, 1)
                .last("LIMIT 1"));
        return primary == null ? null : primary.getSalesUserId();
    }

    @Override
    public Map<Long, EngineerPrimarySalesDto> mapPrimaryByEngineerIds(List<Long> engineerIds) {
        if (engineerIds == null || engineerIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return baseMapper.selectActivePrimaryByEngineerIds(engineerIds).stream()
                .collect(Collectors.toMap(EngineerPrimarySalesDto::getEngineerId,
                        Function.identity(), (a, b) -> a));
    }

    @Override
    public List<SalesUserOptionDto> salesUserOptions() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, StatusConstants.ROLE_SALES)
                        .eq(SysUser::getStatus, 1)
                        .orderByAsc(SysUser::getId)).stream()
                .map(u -> new SalesUserOptionDto(u.getId(), u.getRealName(), u.getUsername()))
                .collect(Collectors.toList());
    }

    /** 現任主担当を副担当へ降格する */
    private void demoteCurrentPrimary(List<EngineerSales> actives) {
        actives.stream()
                .filter(es -> es.getPrimaryFlag() != null && es.getPrimaryFlag() == 1)
                .forEach(es -> {
                    es.setPrimaryFlag(0);
                    updateById(es);
                });
    }

    private List<EngineerSales> listActiveEntities(Long engineerId) {
        return list(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, engineerId)
                .isNull(EngineerSales::getReleasedAt));
    }

    private EngineerSales getActiveAssignment(Long engineerId, Long assignmentId) {
        EngineerSales target = getById(assignmentId);
        if (target == null || !target.getEngineerId().equals(engineerId) || target.getReleasedAt() != null) {
            throw BusinessException.of("error.engineerSales.notFound");
        }
        return target;
    }
}
