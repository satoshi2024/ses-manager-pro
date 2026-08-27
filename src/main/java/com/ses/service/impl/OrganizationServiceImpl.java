package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationRelationHistory;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 組織階層・所属の業務ルール実装。 */
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl extends ServiceImpl<OrganizationUnitMapper, OrganizationUnit>
        implements OrganizationService {

    private final UserOrganizationMapper userOrganizationMapper;
    private final CostCenterMapper costCenterMapper;
    private final ManagementBudgetMapper managementBudgetMapper;
    private final MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    private final SysUserMapper sysUserMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    /** 要員の所属組織を扱う。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerMapper engineerMapper;

    /** 親子・状態のasOf解決元。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.OrganizationRelationHistoryMapper relationHistoryMapper;

    /** 要員の会計属性履歴（所属組織・原価部門・想定単価の版）。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.mapper.EngineerAccountingHistoryMapper engineerAccountingHistoryMapper;

    /** 可視範囲の世代番号。既存テストスライス互換のため任意注入。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.security.ScopeVersionRegistry scopeVersionRegistry;

    /**
     * 可視範囲が変わったことをキャッシュ層へ知らせる。
     *
     * <p>コミット後に世代を進める。ロールバックした変更でキャッシュを捨てる必要はなく、
     * またコミット前に進めると、まだ見えないはずの新scopeでキャッシュが作られてしまう。
     */
    private void markScopeChanged() {
        if (scopeVersionRegistry == null) {
            return;
        }
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            scopeVersionRegistry.bump();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        scopeVersionRegistry.bump();
                    }
                });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(OrganizationUnit entity) {
        validateOrganization(entity, null);
        boolean saved = super.save(entity);
        if (saved) {
            // 初版を組織の有効開始日から記録する。これが無いとasOf解決が現在値へ落ちる。
            recordRelation(entity.getId(), entity.getParentId(), entity.getStatus(), entity.getValidFrom());
            markScopeChanged();
        }
        return saved;
    }

    @Override
    public boolean updateById(OrganizationUnit entity) {
        validateOrganization(entity, entity.getId());
        return super.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateOrganization(OrganizationUnit entity, Integer expectedVersion) {
        if (entity == null || entity.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        OrganizationUnit current = getById(entity.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        entity.setTenantId(current.getTenantId());
        validateOrganization(entity, entity.getId());
        entity.setVersion(expectedVersion);
        // OptimisticLockerInnerInterceptor が version を検査し、成功時に +1 する。
        // 同じIDを更新するので、所属・原価部門・予算・snapshotの参照は一切壊れない。
        if (baseMapper.updateById(entity) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        recordRelation(entity.getId(), entity.getParentId(),
                entity.getStatus() == null ? current.getStatus() : entity.getStatus(), LocalDate.now());
        markScopeChanged();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean merge(Long organizationId, Long targetOrganizationId, Integer expectedVersion) {
        if (organizationId == null || targetOrganizationId == null || organizationId.equals(targetOrganizationId)) {
            throw BusinessException.of("error.organization.invalid");
        }
        // source/target とも同一トランザクション内で行ロックしてから検査する。
        // ロック前に読むと、検査通過後に相手が無効化・統合されても気付けない。
        OrganizationUnit current = baseMapper.selectByIdForUpdate(organizationId);
        OrganizationUnit target = baseMapper.selectByIdForUpdate(targetOrganizationId);
        if (current == null) {
            current = getById(organizationId);
        }
        if (target == null) {
            target = getById(targetOrganizationId);
        }
        if (current == null || target == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        if (!Objects.equals(current.getLegalEntityId(), target.getLegalEntityId())) {
            throw BusinessException.of("error.organization.legalEntityMismatch");
        }
        if (expectedVersion == null || !Objects.equals(expectedVersion, current.getVersion())) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        // 統合日に業務上使えない組織へ付け替えると、所属・原価部門・予算の行き先が
        // その場で不可視（scope外・期間外）になり、以後の維持操作もできなくなる。
        // 統合元が既に統合済み・無効の場合も二重統合になるため拒否する。
        if (current.getMergedInto() != null) {
            throw BusinessException.of("error.organization.merge.alreadyMerged");
        }
        assertUsableForMerge(current, today, "error.organization.merge.sourceUnusable");
        assertUsableForMerge(target, today, "error.organization.merge.targetUnusable");
        // 統合先が統合元の子孫だと、親子関係の付け替えで循環する。
        if (descendantIds(organizationId, today).contains(targetOrganizationId)) {
            throw BusinessException.of("error.organization.cycle");
        }

        // 子組織を統合先へ付け替える。付け替えは統合日から有効とし、旧親は履歴に残す。
        // 履歴を残さないと「統合前の日付の組織ツリー」が今日の親を返してしまう。
        for (OrganizationUnit child : list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getParentId, organizationId))) {
            child.setParentId(targetOrganizationId);
            if (baseMapper.updateById(child) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
            recordRelation(child.getId(), targetOrganizationId, child.getStatus(), today);
        }
        // 有効な所属は統合日で旧行を閉じ、統合先の新しい履歴行を作る。
        // 旧行のorganization_idを直接書き換えると、統合日前のas-of照会まで改変される。
        //
        // 対象は「統合日時点でまだ終了していない」所属全部。valid_to IS NULL だけを対象にすると、
        // 期間限定（valid_to が統合日以降）の現在有効な所属を取りこぼし、無効化された統合元組織に
        // 有効な所属だけが残ってしまう（第十三次Review P1-2）。既に統合日より前に終了した所属は
        // 対象外（過去の事実は動かさない）。
        for (UserOrganization assignment : userOrganizationMapper
                .selectByUserOrganizationForUpdate(organizationId, today)) {
            // 元の終了日を保持し、統合先の既存所属で覆われた部分だけを差し引く。
            // 「重複が1件でもあればcontinue」すると、部分重複の前後の所属期間を失う。
            LocalDate originalFrom = assignment.getValidFrom();
            LocalDate originalValidTo = assignment.getValidTo();
            LocalDate relocationFrom = originalFrom.isBefore(today) ? today : originalFrom;
            List<UserOrganization> userAssignments = userOrganizationMapper
                    .selectByUserForUpdate(assignment.getUserId());
            List<UserOrganization> targetAssignments = userAssignments.stream()
                    .filter(existing -> !Objects.equals(existing.getId(), assignment.getId())
                            && Objects.equals(existing.getOrganizationId(), targetOrganizationId)
                            && overlaps(existing.getValidFrom(), existing.getValidTo(), relocationFrom, originalValidTo))
                    .sorted(Comparator.comparing(UserOrganization::getValidFrom))
                    .toList();

            List<DateRange> uncovered = subtractCovered(relocationFrom, originalValidTo, targetAssignments);
            boolean futureSource = !originalFrom.isBefore(today);
            boolean canUpdateSourceInPlace = !uncovered.isEmpty()
                    && uncovered.get(0).from().equals(relocationFrom);

            if (originalFrom.isBefore(today)) {
                // 過去部分は旧組織の事実として昨日で閉じる。
                assignment.setValidTo(today.minusDays(1));
                if (userOrganizationMapper.updateById(assignment) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
            } else if (!canUpdateSourceInPlace) {
                // 未来開始の元行が統合先の前段から覆われる場合は、元の全期間を残さない。
                // 残したまま未被覆の尾段だけを追加すると、無効な統合元と統合先が重複する。
                if (userOrganizationMapper.deleteById(assignment.getId()) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
            }

            if (!uncovered.isEmpty() && futureSource && canUpdateSourceInPlace) {
                // 同日開始を含む未開始行は、逆向きの valid_to を作らず既存行を原地更新する。
                // targetの主所属昇格より前に行い、sourceが主所属でも一意制約に触れないようにする。
                DateRange first = uncovered.remove(0);
                assignment.setOrganizationId(targetOrganizationId);
                assignment.setValidFrom(first.from());
                assignment.setValidTo(first.to());
                if (userOrganizationMapper.updateById(assignment) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
            }

            // sourceを閉じる・削除する処理を先に行い、同一ユーザーの主所属一意制約を
            // 解放してから、sourceの移行期間と重なるtarget区間だけを主所属へ切り替える。
            if (isPrimary(assignment)) {
                for (UserOrganization targetAssignment : targetAssignments) {
                    splitTargetPrimaryWindow(targetAssignment, relocationFrom, originalValidTo);
                }
            }

            for (DateRange range : uncovered) {
                UserOrganization successor = new UserOrganization();
                successor.setUserId(assignment.getUserId());
                successor.setOrganizationId(targetOrganizationId);
                successor.setPositionName(assignment.getPositionName());
                successor.setManagerUserId(assignment.getManagerUserId());
                successor.setPrimaryFlag(assignment.getPrimaryFlag());
                successor.setValidFrom(range.from());
                successor.setValidTo(range.to());
                successor.setVersion(0);
                if (userOrganizationMapper.insert(successor) != 1) {
                    throw BusinessException.of("error.organization.assignment.saveFailed");
                }
            }
        }
        // 要員の所属組織も付け替える。
        if (engineerMapper != null) {
            engineerMapper.reassignOrganization(organizationId, targetOrganizationId);
        }
        // 要員の会計属性履歴（V62）も同じ統合日から新しい所属組織を記録する。
        // t_engineer.organization_id だけを書き換えると、統合日より前の日付でBench待機原価を
        // 照会したときに統合後の組織へ焼き付いてしまう（現行版だけを閉じて新版を作る）。
        if (engineerAccountingHistoryMapper != null) {
            List<com.ses.entity.EngineerAccountingHistory> currentHistories =
                    engineerAccountingHistoryMapper.selectCurrentByOrganizationId(organizationId);
            for (com.ses.entity.EngineerAccountingHistory history : currentHistories) {
                if (history.getValidFrom() != null && !history.getValidFrom().isBefore(today)) {
                    // 同日（および未来開始）の現行版は閉じずに組織だけ差し替える。
                    // today.minusDays(1)へ閉じると valid_from > valid_to の逆向き履歴になる。
                    history.setOrganizationId(targetOrganizationId);
                    if (engineerAccountingHistoryMapper.updateById(history) != 1) {
                        throw BusinessException.of(409, "error.organization.versionConflict");
                    }
                    continue;
                }
                // 過去開始の現行版だけを昨日で閉じ、統合日から新しい版を開始する。
                history.setValidTo(today.minusDays(1));
                if (engineerAccountingHistoryMapper.updateById(history) != 1) {
                    throw BusinessException.of(409, "error.organization.versionConflict");
                }
                engineerAccountingHistoryMapper.insert(com.ses.entity.EngineerAccountingHistory.builder()
                        .engineerId(history.getEngineerId())
                        .organizationId(targetOrganizationId)
                        .organizationHistoryStatus(history.getOrganizationHistoryStatus())
                        .costCenterId(history.getCostCenterId())
                        .expectedUnitPrice(history.getExpectedUnitPrice())
                        .validFrom(today).validTo(null).build());
            }
        }
        // 原価部門は組織にぶら下がるマスタなので全件付け替える。
        for (CostCenter center : costCenterMapper.selectList(new LambdaQueryWrapper<CostCenter>()
                .eq(CostCenter::getOrganizationId, organizationId))) {
            center.setOrganizationId(targetOrganizationId);
            if (costCenterMapper.updateById(center) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        // 予算は当月以降だけ移す。過去月の予実は確定済みの比較対象なので動かさない(R2.2)。
        for (ManagementBudget budget : managementBudgetMapper.selectList(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, organizationId)
                .ge(ManagementBudget::getBudgetMonth, monthStart))) {
            boolean duplicated = managementBudgetMapper.selectCount(new LambdaQueryWrapper<ManagementBudget>()
                    .eq(ManagementBudget::getOrganizationId, targetOrganizationId)
                    .eq(ManagementBudget::getBudgetMonth, budget.getBudgetMonth())
                    .eq(budget.getCostCenterId() != null, ManagementBudget::getCostCenterId, budget.getCostCenterId())
                    .isNull(budget.getCostCenterId() == null, ManagementBudget::getCostCenterId)) > 0;
            if (duplicated) {
                throw BusinessException.of("error.organization.merge.budgetConflict");
            }
            budget.setOrganizationId(targetOrganizationId);
            if (managementBudgetMapper.updateById(budget) != 1) {
                throw BusinessException.of(409, "error.organization.budget.conflict");
            }
        }
        // 月次snapshotは動かさない。過去の帰属を書き換えないのがR2.2の要件そのもの。
        current.setMergedInto(targetOrganizationId);
        current.setStatus("無効");
        current.setVersion(expectedVersion);
        if (baseMapper.updateById(current) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        // 無効化も統合日から。統合前の日付では統合元が「有効」として解決される必要がある
        // （でないと統合元の部門責任者が統合前の自組織データを遡って見られなくなる）。
        recordRelation(organizationId, current.getParentId(), "無効", today);
        markScopeChanged();
        return true;
    }

    /**
     * 指定日時点の組織ツリー。親子・状態は履歴から解決する。
     *
     * <p>現在の{@code parent_id}/{@code status}を読むと、今日の統合結果が「昨日のツリー」にも
     * 反映され、過去のscope・部門損益・監査結果が後から変わってしまう（R1.3/R2.2違反）。
     */
    @Override
    public List<OrganizationUnit> listTree(Long legalEntityId, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Map<Long, OrganizationRelationHistory> relations = com.ses.service.security.OrganizationRelationResolver
                .asOfMap(relationHistoryMapper, date);
        return list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(legalEntityId != null, OrganizationUnit::getLegalEntityId, legalEntityId)
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date))
                .orderByAsc(OrganizationUnit::getParentId)
                .orderByAsc(OrganizationUnit::getCode))
                .stream()
                .filter(unit -> "有効".equals(
                        com.ses.service.security.OrganizationRelationResolver.status(relations, unit)))
                // 呼び出し側（画面・API）は parentId をツリー組み立てに使うため、
                // 現在値ではなく指定日時点の親を載せて返す。
                .peek(unit -> unit.setParentId(
                        com.ses.service.security.OrganizationRelationResolver.parentId(relations, unit)))
                .toList();
    }

    @Override
    public List<Long> descendantIds(Long organizationId, LocalDate asOf) {
        if (organizationId == null) {
            return List.of();
        }
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        Map<Long, OrganizationRelationHistory> relations = com.ses.service.security.OrganizationRelationResolver
                .asOfMap(relationHistoryMapper, date);
        List<OrganizationUnit> units = list(new LambdaQueryWrapper<OrganizationUnit>()
                .le(OrganizationUnit::getValidFrom, date)
                .and(w -> w.isNull(OrganizationUnit::getValidTo)
                        .or().ge(OrganizationUnit::getValidTo, date)))
                .stream()
                .filter(unit -> "有効".equals(
                        com.ses.service.security.OrganizationRelationResolver.status(relations, unit)))
                .toList();
        Set<Long> result = new HashSet<>();
        result.add(organizationId);
        boolean changed;
        do {
            changed = false;
            for (OrganizationUnit unit : units) {
                Long parentId = com.ses.service.security.OrganizationRelationResolver.parentId(relations, unit);
                if (unit.getId() != null && parentId != null && result.contains(parentId)) {
                    changed |= result.add(unit.getId());
                }
            }
        } while (changed);
        return new ArrayList<>(result);
    }

    /**
     * 親子・状態の変更を履歴へ記録する。現行版を前日で閉じ、変更日から新しい版を開く。
     *
     * <p>同日内の複数回変更は最後の値で上書きする（同じ日に2つの版を持たせない）。
     */
    private void recordRelation(Long organizationId, Long parentId, String status, LocalDate effectiveFrom) {
        if (relationHistoryMapper == null || organizationId == null) {
            return;
        }
        OrganizationRelationHistory currentRow = relationHistoryMapper.selectCurrent(organizationId);
        if (currentRow != null
                && Objects.equals(currentRow.getParentId(), parentId)
                && Objects.equals(currentRow.getStatus(), status)) {
            return;
        }
        if (currentRow != null) {
            if (!currentRow.getValidFrom().isBefore(effectiveFrom)) {
                // 同日（またはそれ以降に開始した）版は履歴を増やさず更新する。
                currentRow.setParentId(parentId);
                currentRow.setStatus(status);
                relationHistoryMapper.updateById(currentRow);
                return;
            }
            relationHistoryMapper.closeCurrent(organizationId, effectiveFrom.minusDays(1));
        }
        relationHistoryMapper.insert(OrganizationRelationHistory.builder()
                .organizationId(organizationId).parentId(parentId).status(status)
                .validFrom(effectiveFrom).validTo(null).build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deactivate(Long organizationId) {
        OrganizationUnit unit = getById(organizationId);
        return unit != null && updateStatus(organizationId, "無効", unit.getVersion());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateStatus(Long organizationId, String status, Integer expectedVersion) {
        if (organizationId == null || expectedVersion == null
                || (!"有効".equals(status) && !"無効".equals(status))) {
            throw BusinessException.of(400, "error.organization.invalid");
        }
        OrganizationUnit current = baseMapper.selectByIdForUpdate(organizationId);
        if (current == null) {
            current = getById(organizationId);
        }
        if (current == null) {
            return false;
        }
        LocalDate today = LocalDate.now();
        if ("無効".equals(status)) {
            if (count(new LambdaQueryWrapper<OrganizationUnit>()
                    .eq(OrganizationUnit::getParentId, organizationId)
                    .eq(OrganizationUnit::getStatus, "有効")) > 0) {
                throw BusinessException.of("error.organization.deactivate.hasChildren");
            }
            // 「終了日が未設定」だけを在籍と見なすと、終了日が未来の在籍者を取りこぼす。
            // 未開始（valid_from が未来）の所属も、無効化後に開始してしまうため同じく拒否する。
            if (userOrganizationMapper.selectCount(new LambdaQueryWrapper<UserOrganization>()
                    .eq(UserOrganization::getOrganizationId, organizationId)
                    .and(w -> w.isNull(UserOrganization::getValidTo)
                            .or().ge(UserOrganization::getValidTo, today))) > 0) {
                throw BusinessException.of("error.organization.deactivate.hasMembers");
            }
        }
        OrganizationUnit patch = new OrganizationUnit();
        patch.setId(organizationId);
        patch.setStatus(status);
        patch.setVersion(expectedVersion);
        if (baseMapper.updateById(patch) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        recordRelation(organizationId, current.getParentId(), status, today);
        markScopeChanged();
        return true;
    }

    @Override
    public boolean isReferenced(Long organizationId) {
        if (organizationId == null) {
            return false;
        }
        return count(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getParentId, organizationId)) > 0
                || userOrganizationMapper.selectCount(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getOrganizationId, organizationId)) > 0
                || costCenterMapper.selectCount(new LambdaQueryWrapper<CostCenter>()
                .eq(CostCenter::getOrganizationId, organizationId)) > 0
                || managementBudgetMapper.selectCount(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, organizationId)) > 0
                || monthlyAccountingDimensionMapper.selectCount(
                new LambdaQueryWrapper<MonthlyAccountingDimension>()
                        .eq(MonthlyAccountingDimension::getOrganizationId, organizationId)) > 0
                || (engineerMapper != null && engineerMapper.selectCount(
                new LambdaQueryWrapper<com.ses.entity.Engineer>()
                        .eq(com.ses.entity.Engineer::getOrganizationId, organizationId)) > 0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        Long organizationId = Long.valueOf(id.toString());
        if (isReferenced(organizationId)) {
            throw BusinessException.of("error.organization.delete.referenced");
        }
        return super.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserOrganization assignUser(UserOrganization assignment) {
        if (sysUserMapper.selectByIdForUpdate(assignment.getUserId()) == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
        OrganizationUnit lockedOrganization = baseMapper.selectByIdForUpdate(assignment.getOrganizationId());
        if (lockedOrganization == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
        }
        validateAssignment(assignment, null);
        // validateAssignment は通常の一覧を使うが、先に同一ユーザーの行をロックして
        // primary/期間の二重登録を同時実行で通さない。
        if (locked.stream().anyMatch(item -> overlaps(item.getValidFrom(), item.getValidTo(),
                assignment.getValidFrom(), assignment.getValidTo())
                && (Objects.equals(item.getOrganizationId(), assignment.getOrganizationId())
                || isPrimary(item) && isPrimary(assignment)))) {
            throw BusinessException.of(isPrimary(assignment)
                    ? "error.organization.assignment.primaryOverlap"
                    : "error.organization.assignment.periodOverlap");
        }
        if (userOrganizationMapper.insert(assignment) != 1) {
            throw BusinessException.of("error.organization.assignment.saveFailed");
        }
        markScopeChanged();
        return assignment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUserOrganization(UserOrganization assignment, Integer expectedVersion) {
        if (assignment == null || assignment.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        UserOrganization current = userOrganizationMapper.selectById(assignment.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        assignment.setUserId(current.getUserId());
        validateAssignment(assignment, assignment.getId());
        assignment.setVersion(expectedVersion);
        if (userOrganizationMapper.updateById(assignment) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        markScopeChanged();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserOrganization transferUser(UserOrganization assignment, Integer expectedVersion) {
        if (sysUserMapper.selectByIdForUpdate(assignment.getUserId()) == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
        validateAssignmentBasics(assignment);
        LocalDate transferDate = assignment.getValidFrom();
        // 画面が読んだ「異動元の主所属」が古くなっていないかを検査する。
        // ここを見ないと、他の担当者が先に異動させた後の古いフォームでも異動が通ってしまう。
        UserOrganization currentPrimary = locked.stream()
                .filter(item -> isPrimary(item) && item.getValidTo() == null)
                .findFirst().orElse(null);
        if (currentPrimary != null
                && !transferDate.isAfter(currentPrimary.getValidFrom())) {
            throw BusinessException.of("error.organization.assignment.periodOverlap");
        }
        if (currentPrimary != null
                && (expectedVersion == null || !Objects.equals(expectedVersion, currentPrimary.getVersion()))) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        for (UserOrganization old : locked) {
            if (!overlaps(old.getValidFrom(), old.getValidTo(), transferDate, transferDate)) {
                continue;
            }
            if (Objects.equals(old.getOrganizationId(), assignment.getOrganizationId())) {
                throw BusinessException.of("error.organization.assignment.periodOverlap");
            }
            old.setValidTo(transferDate.minusDays(1));
            if (old.getValidTo().isBefore(old.getValidFrom())) {
                old.setValidTo(old.getValidFrom());
            }
            if (userOrganizationMapper.updateById(old) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        if (userOrganizationMapper.insert(assignment) != 1) {
            throw BusinessException.of("error.organization.assignment.saveFailed");
        }
        markScopeChanged();
        return assignment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseAssignment(Long assignmentId, LocalDate releaseDate, Integer expectedVersion) {
        if (assignmentId == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        UserOrganization current = userOrganizationMapper.selectById(assignmentId);
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        LocalDate date = releaseDate == null ? LocalDate.now() : releaseDate;
        if (date.isBefore(current.getValidFrom())) {
            date = current.getValidFrom();
        }
        UserOrganization patch = new UserOrganization();
        patch.setId(assignmentId);
        patch.setValidTo(date);
        patch.setVersion(expectedVersion);
        if (userOrganizationMapper.updateById(patch) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        markScopeChanged();
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int closeAssignmentsForUser(Long userId, LocalDate releaseDate) {
        if (userId == null) {
            return 0;
        }
        LocalDate date = releaseDate == null ? LocalDate.now() : releaseDate;
        int closed = 0;
        for (UserOrganization assignment : userOrganizationMapper.selectByUserForUpdate(userId)) {
            if (assignment.getValidTo() != null) {
                continue;
            }
            LocalDate validTo = date.isBefore(assignment.getValidFrom()) ? assignment.getValidFrom() : date;
            UserOrganization patch = new UserOrganization();
            patch.setId(assignment.getId());
            patch.setValidTo(validTo);
            patch.setVersion(assignment.getVersion());
            // CAS失敗を黙って捨てると、退職・無効化が「成功」で返るのに所属が開いたまま残り、
            // 退職者が組織scope・部門損益に居座る。トランザクションごと失敗させて再実行させる。
            if (userOrganizationMapper.updateById(patch) != 1) {
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
            closed++;
        }
        // 上長として登録されている行も外す。退職者が上長のまま残ると承認者不在になる。
        userOrganizationMapper.clearManager(userId);
        markScopeChanged();
        return closed;
    }

    @Override
    public java.util.Map<Long, String> namesByIds(java.util.Collection<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> ids = organizationIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> names = new java.util.HashMap<>();
        for (OrganizationUnit unit : listByIds(ids)) {
            names.put(unit.getId(), unit.getName());
        }
        return names;
    }

    @Override
    public List<UserOrganization> listUserOrganizations(Long userId, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, date)
                .and(w -> w.isNull(UserOrganization::getValidTo)
                        .or().ge(UserOrganization::getValidTo, date))
                .orderByDesc(UserOrganization::getPrimaryFlag)
                .orderByAsc(UserOrganization::getValidFrom));
    }

    private void validateOrganization(OrganizationUnit candidate, Long excludedId) {
        if (candidate == null || candidate.getValidFrom() == null
                || candidate.getCode() == null || candidate.getCode().isBlank()
                || candidate.getName() == null || candidate.getName().isBlank()
                || candidate.getType() == null || candidate.getType().isBlank()) {
            throw BusinessException.of("error.organization.invalid");
        }
        validateDateRange(candidate.getValidFrom(), candidate.getValidTo());
        OrganizationUnit current = excludedId == null ? null : getById(excludedId);
        if (current != null && isReferenced(excludedId)
                && (!Objects.equals(current.getValidFrom(), candidate.getValidFrom())
                || !Objects.equals(current.getValidTo(), candidate.getValidTo())
                || !Objects.equals(current.getParentId(), candidate.getParentId()))) {
            throw BusinessException.of("error.organization.referencedPeriodImmutable");
        }
        if (current != null && !Objects.equals(current.getLegalEntityId(), candidate.getLegalEntityId())
                && isReferenced(excludedId)) {
            throw BusinessException.of("error.organization.legalEntityChangeReferenced");
        }
        if (candidate.getParentId() != null) {
            OrganizationUnit parent = getById(candidate.getParentId());
            if (parent == null) {
                throw BusinessException.of("error.organization.parentNotFound");
            }
            if (!Objects.equals(parent.getLegalEntityId(), candidate.getLegalEntityId())) {
                throw BusinessException.of("error.organization.legalEntityMismatch");
            }
            if (Objects.equals(candidate.getId(), candidate.getParentId())
                    || Objects.equals(excludedId, candidate.getParentId())
                    || createsCycle(candidate.getParentId(), candidate.getId(), new HashSet<>())) {
                throw BusinessException.of("error.organization.cycle");
            }
            if (!"有効".equals(parent.getStatus())) {
                throw BusinessException.of("error.organization.parentInactive");
            }
            // 親の有効期間が子を完全に包含していないと、親だけ失効した時点で
            // 「親のいない子」がツリーに残り、scope・人員集計・部門損益が解決不能になる。
            if (!covers(parent.getValidFrom(), parent.getValidTo(),
                    candidate.getValidFrom(), candidate.getValidTo())) {
                throw BusinessException.of("error.organization.parentPeriodNotCovering");
            }
        }
        List<OrganizationUnit> sameCode = list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getCode, candidate.getCode())
                .eq(candidate.getLegalEntityId() != null,
                        OrganizationUnit::getLegalEntityId, candidate.getLegalEntityId())
                .isNull(candidate.getLegalEntityId() == null, OrganizationUnit::getLegalEntityId));
        if (sameCode.stream().anyMatch(existing -> !Objects.equals(existing.getId(), excludedId)
                && overlaps(existing.getValidFrom(), existing.getValidTo(),
                candidate.getValidFrom(), candidate.getValidTo()))) {
            throw BusinessException.of("error.organization.periodOverlap");
        }
    }

    private boolean createsCycle(Long parentId, Long childId, Set<Long> visited) {
        if (parentId == null || Objects.equals(parentId, childId)) {
            return parentId != null;
        }
        if (!visited.add(parentId)) {
            return true;
        }
        OrganizationUnit parent = getById(parentId);
        return parent != null && createsCycle(parent.getParentId(), childId, visited);
    }

    private void validateAssignment(UserOrganization candidate, Long excludedId) {
        validateAssignmentBasics(candidate);
        List<UserOrganization> existing = userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>().eq(UserOrganization::getUserId, candidate.getUserId()));
        boolean overlap = existing.stream().anyMatch(item -> !Objects.equals(item.getId(), excludedId)
                && overlaps(item.getValidFrom(), item.getValidTo(), candidate.getValidFrom(), candidate.getValidTo())
                && (Objects.equals(item.getOrganizationId(), candidate.getOrganizationId())
                || isPrimary(item) && isPrimary(candidate)));
        if (overlap) {
            throw BusinessException.of(isPrimary(candidate)
                    ? "error.organization.assignment.primaryOverlap"
                    : "error.organization.assignment.periodOverlap");
        }
    }

    private void validateAssignmentBasics(UserOrganization candidate) {
        if (candidate == null || candidate.getUserId() == null || candidate.getOrganizationId() == null
                || candidate.getValidFrom() == null) {
            throw BusinessException.of("error.organization.assignment.invalid");
        }
        validateDateRange(candidate.getValidFrom(), candidate.getValidTo());
        if (Objects.equals(candidate.getUserId(), candidate.getManagerUserId())) {
            throw BusinessException.of("error.organization.assignment.managerSelf");
        }
        OrganizationUnit organization = baseMapper.selectByIdForUpdate(candidate.getOrganizationId());
        if (organization == null) {
            organization = getById(candidate.getOrganizationId());
        }
        if (organization == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
        }
        if (!"有効".equals(organization.getStatus())
                || organization.getValidFrom().isAfter(candidate.getValidFrom())
                || (organization.getValidTo() != null && organization.getValidTo().isBefore(candidate.getValidFrom()))) {
            throw BusinessException.of("error.organization.assignment.organizationInactive");
        }
        // 開始日だけ見ると、終了日が組織より後の所属や、有期限の組織への無期限所属を通してしまう。
        // 組織失効後も所属だけが有効なままになり、scope・人員集計・管理会計の帰属が壊れる。
        if (!covers(organization.getValidFrom(), organization.getValidTo(),
                candidate.getValidFrom(), candidate.getValidTo())) {
            throw BusinessException.of("error.organization.assignment.organizationPeriodNotCovering");
        }
        if (candidate.getManagerUserId() != null) {
            SysUser manager = sysUserMapper.selectById(candidate.getManagerUserId());
            if (manager == null || !Integer.valueOf(1).equals(manager.getStatus())
                    || manager.getRole() == null || manager.getRole().isBlank()
                    || "要員".equals(manager.getRole())) {
                throw BusinessException.of("error.organization.assignment.managerNotQualified");
            }
            LocalDate asOf = candidate.getValidFrom();
            if (organizationScopeService != null && !organizationScopeService.hasFullAccess()
                    && !organizationScopeService.isAllowedUser(manager.getId(), asOf)) {
                throw BusinessException.of("error.organization.assignment.managerOutOfScope");
            }
        }
    }

    /** 統合日に「有効」かつ有効期間内であることを要求する。 */
    private void assertUsableForMerge(OrganizationUnit unit, LocalDate mergeDate, String messageKey) {
        boolean active = "有効".equals(unit.getStatus());
        boolean started = unit.getValidFrom() != null && !unit.getValidFrom().isAfter(mergeDate);
        boolean notExpired = unit.getValidTo() == null || !unit.getValidTo().isBefore(mergeDate);
        if (!active || !started || !notExpired) {
            throw BusinessException.of(messageKey);
        }
    }

    private boolean isPrimary(UserOrganization assignment) {
        return Integer.valueOf(1).equals(assignment.getPrimaryFlag());
    }

    /** 統合元の残存期間から、統合先の既存所属期間を引いた未被覆区間。 */
    private List<DateRange> subtractCovered(LocalDate from, LocalDate to,
                                            List<UserOrganization> coveredAssignments) {
        List<DateRange> result = new ArrayList<>();
        LocalDate cursor = from;
        LocalDate end = to == null ? LocalDate.MAX : to;
        for (UserOrganization covered : coveredAssignments) {
            LocalDate coveredFrom = covered.getValidFrom().isBefore(from) ? from : covered.getValidFrom();
            LocalDate coveredTo = covered.getValidTo() == null || covered.getValidTo().isAfter(end)
                    ? end : covered.getValidTo();
            if (cursor.isBefore(coveredFrom)) {
                result.add(new DateRange(cursor, coveredFrom.minusDays(1)));
            }
            if (!cursor.isAfter(coveredTo)) {
                if (coveredTo.equals(end)) {
                    return result;
                }
                cursor = coveredTo.plusDays(1);
            }
            if (cursor.isAfter(end)) {
                return result;
            }
        }
        if (!cursor.isAfter(end)) {
            result.add(new DateRange(cursor, to));
        }
        return result;
    }

    /** target所属をsourceの移行期間に合わせて分割し、重複区間だけを主所属にする。 */
    private void splitTargetPrimaryWindow(UserOrganization targetAssignment,
                                          LocalDate primaryFrom, LocalDate primaryTo) {
        LocalDate targetFrom = targetAssignment.getValidFrom();
        LocalDate targetTo = targetAssignment.getValidTo();
        LocalDate overlapFrom = targetFrom.isAfter(primaryFrom) ? targetFrom : primaryFrom;
        LocalDate overlapTo = minEnd(targetTo, primaryTo);
        if (overlapTo != null && overlapFrom.isAfter(overlapTo)) {
            return;
        }

        List<AssignmentSegment> segments = new ArrayList<>();
        if (targetFrom.isBefore(overlapFrom)) {
            segments.add(new AssignmentSegment(new DateRange(targetFrom, overlapFrom.minusDays(1)),
                    targetAssignment.getPrimaryFlag()));
        }
        segments.add(new AssignmentSegment(new DateRange(overlapFrom, overlapTo), 1));
        if (overlapTo != null) {
            LocalDate afterFrom = overlapTo.plusDays(1);
            if (targetTo == null || !afterFrom.isAfter(targetTo)) {
                segments.add(new AssignmentSegment(new DateRange(afterFrom, targetTo),
                        targetAssignment.getPrimaryFlag()));
            }
        }

        applyAssignmentSegment(targetAssignment, segments.get(0).range());
        targetAssignment.setPrimaryFlag(segments.get(0).primaryFlag());
        if (userOrganizationMapper.updateById(targetAssignment) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        for (int i = 1; i < segments.size(); i++) {
            AssignmentSegment segment = segments.get(i);
            UserOrganization successor = UserOrganization.builder()
                    .userId(targetAssignment.getUserId())
                    .organizationId(targetAssignment.getOrganizationId())
                    .positionName(targetAssignment.getPositionName())
                    .managerUserId(targetAssignment.getManagerUserId())
                    .primaryFlag(segment.primaryFlag())
                    .validFrom(segment.range().from())
                    .validTo(segment.range().to())
                    .version(0)
                    .build();
            if (userOrganizationMapper.insert(successor) != 1) {
                throw BusinessException.of("error.organization.assignment.saveFailed");
            }
        }
    }

    private void applyAssignmentSegment(UserOrganization assignment, DateRange range) {
        assignment.setValidFrom(range.from());
        assignment.setValidTo(range.to());
    }

    private LocalDate minEnd(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private record DateRange(LocalDate from, LocalDate to) {
    }

    private record AssignmentSegment(DateRange range, Integer primaryFlag) {
    }

    /**
     * outer が inner の期間を完全に包含するか。
     *
     * <p>終了日 {@code null} は無期限。無期限の inner を有期限の outer へぶら下げることはできない
     * （outer 失効後も inner だけが残り、時間的に無効な関係になる）。
     */
    private boolean covers(LocalDate outerFrom, LocalDate outerTo, LocalDate innerFrom, LocalDate innerTo) {
        if (outerFrom != null && innerFrom != null && outerFrom.isAfter(innerFrom)) {
            return false;
        }
        if (outerTo == null) {
            return true;
        }
        return innerTo != null && !innerTo.isAfter(outerTo);
    }

    private boolean overlaps(LocalDate from1, LocalDate to1, LocalDate from2, LocalDate to2) {
        LocalDate max1 = to1 == null ? LocalDate.MAX : to1;
        LocalDate max2 = to2 == null ? LocalDate.MAX : to2;
        return !from1.isAfter(max2) && !from2.isAfter(max1);
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw BusinessException.of("error.organization.invalidPeriod");
        }
    }
}
