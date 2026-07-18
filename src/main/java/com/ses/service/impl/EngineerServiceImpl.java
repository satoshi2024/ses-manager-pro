package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.Proposal;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;

/**
 * エンジニアサービス実装クラス
 */
@Service
@RequiredArgsConstructor
public class EngineerServiceImpl extends ServiceImpl<EngineerMapper, Engineer> implements EngineerService {

    private final ContractMapper contractMapper;
    private final ProposalMapper proposalMapper;
    private final EngineerSalesService engineerSalesService;
    private final com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    private final com.ses.mapper.SysUserMapper sysUserMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Long engineerId = Long.valueOf(id.toString());
        long active = contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, StatusConstants.CONTRACT_ACTIVE));
        if (active > 0) {
            throw BusinessException.of("error.engineer.delete.activeContract");
        }
        long openProposals = proposalMapper.selectCount(new LambdaQueryWrapper<Proposal>()
                .eq(Proposal::getEngineerId, engineerId)
                .notIn(Proposal::getStatus, List.of("成約", "見送り")));
        if (openProposals > 0) {
            throw BusinessException.of("error.engineer.delete.activeProposal");
        }
        boolean removed = super.removeById(id);
        // 削除が成功したときだけ現任の担当営業割当を解除する（released_at 設定。履歴保全のため
        // 論理削除はしない）。削除失敗(false)時に解除だけがコミットされるのを防ぐ（review-fixes G3）。
        if (removed) {
            engineerSalesService.releaseAllByEngineerId(engineerId);
            // 削除成功後にのみ、要員アカウントの紐付けを解除し当該ユーザーを無効化する（G3規約）。
            com.ses.entity.EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
            if (link != null) {
                Long userId = link.getSysUserId();
                engineerAccountLinkService.unlinkByEngineerId(engineerId);
                com.ses.entity.SysUser user = sysUserMapper.selectById(userId);
                if (user != null) {
                    user.setStatus(0);
                    sysUserMapper.updateById(user);
                }
            }
        }
        return removed;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateWithStatusGuard(Engineer engineer) {
        Engineer old = getById(engineer.getId());
        if (old != null && engineer.getStatus() != null && !engineer.getStatus().equals(old.getStatus())) {
            long active = contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getEngineerId, engineer.getId())
                    .eq(Contract::getStatus, StatusConstants.CONTRACT_ACTIVE));
            if (StatusConstants.ENGINEER_ACTIVE.equals(engineer.getStatus()) && active == 0) {
                throw BusinessException.of("error.engineer.statusActiveNoContract");
            }
            if (StatusConstants.ENGINEER_BENCH.equals(engineer.getStatus()) && active > 0) {
                throw BusinessException.of("error.engineer.statusBenchHasContract");
            }
        }
        return updateById(engineer);
    }
}




