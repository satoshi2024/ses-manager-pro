package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerPrimarySalesDto;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.engineersales.SalesUserOptionDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.security.ScopeChangeInvalidator;
import com.ses.service.security.epoch.ActorShareLock;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.EngineerEpochAnchors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 要員担当営業サービス実装
 *
 * <p>NF01 F2（細粒度epoch計画 §4.2、§3.4）: 担当営業の割当は要員の営業DataScope（述語(e)(f)）を変えるため、
 * 割当行（lock rank 10）を書く前に要員行（rank 7、engineer epochのanchor）を最初にX lockし、行が変わった時だけ
 * 同じtransactionでengineer epochを1進める。割当では、その前に営業ユーザー行（rank 3）を共有lockして有効性を
 * lock下で読み直す。tenantはlockした要員行の値を使う。主担当フラグは述語に入らないが、子行の変化は一律に進める
 * （規則を単純に保つ）。割当行は列限定の更新だけで書く（entityの全体更新で他列を古い値へ戻さない。U4b）。</p>
 */
@Service
@RequiredArgsConstructor
public class EngineerSalesServiceImpl extends ServiceImpl<EngineerSalesMapper, EngineerSales>
        implements EngineerSalesService {

    private final SysUserMapper sysUserMapper;
    private final EngineerMapper engineerMapper;
    private final ActorShareLock actorShareLock;
    private final AuthorizationEpochFence authorizationEpochFence;

    /** DataScope invalidation。既存テストスライス（手動構築）互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScopeChangeInvalidator scopeChangeInvalidator;

    @Override
    public List<EngineerSalesDto> listActive(Long engineerId) {
        return baseMapper.selectActiveWithNames(engineerId);
    }

    @Override
    public List<EngineerSalesDto> listHistory(Long engineerId) {
        return baseMapper.selectHistoryWithNames(engineerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long engineerId, Long salesUserId, boolean primaryFlag, String remarks) {
        // 計画 §3.4: 3 S{営業ユーザー} → 7 X{要員} → 10。営業ユーザーの有効性は共有lock下の現在値で判定し、
        // 並行する無効化・role変更・削除（rank 3 X）の前後どちらかに直列化する。
        SysUser user = salesUserId == null ? null : actorShareLock.lockActive(salesUserId, null);
        if (user == null || !StatusConstants.ROLE_SALES.equals(user.getRole())
                || user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.of("error.engineerSales.notSalesRole");
        }
        AuthorizationEpochAnchor.Engineer anchor = lockEngineer(engineerId);

        // 重複判定はanchor取得後に行う（割当行のwriterはすべて同じ要員行のX lockで直列化される）。
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
        authorizationEpochFence.advance(anchor);
        // 要員↔担当営業の割当はDataScope（担当要員/担当契約の母集団）を変える。
        // 進めないと、割当直後もDashboardキャッシュのTTLが切れるまで旧scopeの集計が返る
        // （第十四次Review P1-3）。
        invalidateScope();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setPrimary(Long engineerId, Long assignmentId) {
        // 計画 §4.2: 7 X{要員} → 10。割当行はlock後に読む。
        AuthorizationEpochAnchor.Engineer anchor = lockEngineer(engineerId);
        EngineerSales target = getActiveAssignment(engineerId, assignmentId);
        if (target.getPrimaryFlag() != null && target.getPrimaryFlag() == 1) {
            return; // 既に主担当なら行は変わらないため世代も進めない（E-4）
        }
        demoteCurrentPrimary(listActiveEntities(engineerId));
        updatePrimaryFlag(target.getId(), 1);
        authorizationEpochFence.advance(anchor);
        invalidateScope();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(Long engineerId, Long assignmentId) {
        // 計画 §4.2: 7 X{要員} → 10。割当行はlock後に読む。
        AuthorizationEpochAnchor.Engineer anchor = lockEngineer(engineerId);
        EngineerSales target = getActiveAssignment(engineerId, assignmentId);
        List<EngineerSales> actives = listActiveEntities(engineerId);
        boolean isPrimary = target.getPrimaryFlag() != null && target.getPrimaryFlag() == 1;
        if (isPrimary && actives.size() > 1) {
            throw BusinessException.of("error.engineerSales.primaryReleaseBlocked");
        }
        // 列限定の更新（現任の行だけ）。履歴保全のため論理削除はしない。
        if (!update(new LambdaUpdateWrapper<EngineerSales>()
                .eq(EngineerSales::getId, target.getId())
                .isNull(EngineerSales::getReleasedAt)
                .set(EngineerSales::getReleasedAt, LocalDate.now())
                .set(EngineerSales::getUpdatedAt, LocalDateTime.now()))) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        authorizationEpochFence.advance(anchor);
        invalidateScope();
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
    public boolean isActiveSalesUser(Long userId) {
        if (userId == null) {
            return false;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user != null
                && StatusConstants.ROLE_SALES.equals(user.getRole())
                && Integer.valueOf(1).equals(user.getStatus())
                && !Integer.valueOf(1).equals(user.getDeletedFlag());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseAllByEngineerId(Long engineerId) {
        releaseAllByEngineerId(engineerId, LocalDate.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void releaseAllByEngineerId(Long engineerId, LocalDate releasedAt) {
        if (engineerId == null) {
            return;
        }
        // 計画 §4.2: 7 X{要員}（要員削除・退社完了の呼出元が保持済みなら同じ行の再取得になるだけ）→ 10。
        // 要員削除の後段から呼ばれるため、論理削除済みの要員行もlockする。
        Engineer locked = engineerMapper.selectScopeAnchorForUpdate(engineerId);
        AuthorizationEpochAnchor.Engineer anchor = locked == null ? null : EngineerEpochAnchors.of(locked);
        LocalDate releaseDate = releasedAt != null ? releasedAt : LocalDate.now();
        // wrapper 更新は MetaObjectHandler(updated_at 自動設定)が発火しないため明示的に設定する
        // （review-fixes G4）。
        boolean released = update(new LambdaUpdateWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, engineerId)
                .isNull(EngineerSales::getReleasedAt)
                .set(EngineerSales::getReleasedAt, releaseDate)
                .set(EngineerSales::getUpdatedAt, LocalDateTime.now()));
        // 現任の割当が無ければ行は変わらないため進めない（E-4）。要員行が無い孤立割当はanchorが無く、
        // CONFIRMのreaderもその要員を読めないため増分の対象にしない。
        if (released && anchor != null) {
            authorizationEpochFence.advance(anchor);
        }
        invalidateScope();
    }

    private void invalidateScope() {
        if (scopeChangeInvalidator != null) {
            scopeChangeInvalidator.invalidate();
        }
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

    /**
     * 要員行（lock rank 7）をX lockし、DBの {@code tenant_id} でanchorを作る。行が無いか論理削除済みなら404、
     * tenant未解決の行は409（{@link EngineerEpochAnchors#of}）。
     */
    private AuthorizationEpochAnchor.Engineer lockEngineer(Long engineerId) {
        Engineer locked = engineerId == null ? null : engineerMapper.selectScopeAnchorForUpdate(engineerId);
        if (locked == null || Integer.valueOf(1).equals(locked.getDeletedFlag())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return EngineerEpochAnchors.of(locked);
    }

    /** 現任主担当を副担当へ降格する */
    private void demoteCurrentPrimary(List<EngineerSales> actives) {
        actives.stream()
                .filter(es -> es.getPrimaryFlag() != null && es.getPrimaryFlag() == 1)
                .forEach(es -> updatePrimaryFlag(es.getId(), 0));
    }

    /** 現任の割当行の主担当フラグだけを書く列限定の更新。wrapper更新は updated_at を自動設定しないため明示する。 */
    private void updatePrimaryFlag(Long assignmentId, int primaryFlag) {
        if (!update(new LambdaUpdateWrapper<EngineerSales>()
                .eq(EngineerSales::getId, assignmentId)
                .isNull(EngineerSales::getReleasedAt)
                .set(EngineerSales::getPrimaryFlag, primaryFlag)
                .set(EngineerSales::getUpdatedAt, LocalDateTime.now()))) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
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
