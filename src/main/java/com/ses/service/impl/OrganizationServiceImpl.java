package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CostCenter;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.ManagementBudget;
import com.ses.entity.MonthlyAccountingDimension;
import com.ses.entity.OrganizationRelationHistory;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.MonthlyAccountingDimensionMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import com.ses.service.security.epoch.ActorEpochAnchors;
import com.ses.service.security.epoch.ActorShareLock;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.security.epoch.EngineerEpochAnchors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 組織階層・所属の業務ルール実装。
 *
 * <p>NF01 F2（細粒度epoch計画 §2.2・§3.4・§4.2、W3-16a後半）: 組織行は組織部分木epochのanchor（lock rank 6）、
 * 利用者行は actor epoch（rank 3）、要員行は engineer epoch（rank 7）のanchorである。ここの全writerは、述語を変える
 * 書込みの前にanchorを rank 順（3 → 6 → 7 → 8 所属行 → 11 会計履歴 → 葉）・同じrank内はID昇順にX lockし、lock後の
 * 行で旧値を読んでから書き、値が変わった時だけ同じtransactionでepochを進める（E-1・E-2・E-4）。</p>
 *
 * <ul>
 *   <li>組織階層writer（登録・属性更新・状態変更・削除・統合）: 変化した組織自身と旧・新の祖先鎖（自身を含む）の
 *       組織部分木epochを1回ずつ進める。表示属性（名称・コード・種別）だけの更新では進めない。</li>
 *   <li>所属writer（配属・更新・異動・解除・一括終了）: 本人のactor epochと、本人の連携要員のengineer epochを進める
 *       （連携要員の組織帰属・上長経路は所属行を読むため。計画 §2.2）。上長として登録された他人の行の上長解除
 *       （{@code clearManager}）は上長本人の述語だけを変えるので、本人のactor epochで覆う。</li>
 *   <li>anchorのtenantは要求・contextではなく、lockした行の値を使う。discoveryを伴うため全writerを
 *       {@code READ_COMMITTED} に固定し（E-3）、discoveryとlock後の読み直しが違えば自動再試行せず409で止める。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl extends ServiceImpl<OrganizationUnitMapper, OrganizationUnit>
        implements OrganizationService {

    private final UserOrganizationMapper userOrganizationMapper;
    private final CostCenterMapper costCenterMapper;
    private final ManagementBudgetMapper managementBudgetMapper;
    private final MonthlyAccountingDimensionMapper monthlyAccountingDimensionMapper;
    private final SysUserMapper sysUserMapper;

    /** 本人の連携要員の発見（rank 7 のdiscovery。紐付けは本人の利用者行のX lock下で安定する）。 */
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;

    /** NF01 細粒度epochの増分（組織部分木・actor・engineer。計画 §2.4）。 */
    private final AuthorizationEpochFence authorizationEpochFence;

    /** 所属行に複写・設定する上長の利用者行の共有lock（rank 3 S。FK検査のS lockを葉の後に取らないため）。 */
    private final ActorShareLock actorShareLock;

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

    /**
     * 組織を登録する。新しい親の祖先鎖（rank 6）をID昇順にX lockしてから検査・登録し、新しい子が加わる祖先鎖の
     * 組織部分木epochを1進める（計画 §4.2）。新規行自身は DEFAULT 0 のまま（まだ提出が存在しない）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean save(OrganizationUnit entity) {
        Map<Long, OrganizationUnit> lockedOrganizations = entity == null ? Map.of()
                : lockOrganizations(idSet(entity.getParentId()), List.of());
        validateOrganization(entity, null);
        boolean saved = super.save(entity);
        if (saved) {
            // 初版を組織の有効開始日から記録する。これが無いとasOf解決が現在値へ落ちる。
            recordRelation(entity.getId(), entity.getParentId(), entity.getStatus(), entity.getValidFrom());
            advanceOrganizations(lockedOrganizations.keySet());
            markScopeChanged();
        }
        return saved;
    }

    // 公開の updateById override は持たない（計画 §11 F-8）。transaction・親子履歴・epochの増分なしに parent_id・status を
    // 書ける入口になるため、組織属性の更新は updateOrganization だけで行う（継承した updateById の呼出しは U4b が検出する）。

    /**
     * 組織属性を版番号CASで更新する。discovery{自身と旧祖先鎖・新しい親の祖先鎖} → rank 6 X（ID昇順）→ lock後の鎖の
     * 読み直しで一致を確認してから旧値を読み、親・状態・有効期間・統合先・親子履歴が変わった時だけ、lockした組織
     * （自身と旧・新の祖先鎖）の組織部分木epochを1回ずつ進める。表示属性だけの更新では進めない（計画 §2.2）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateOrganization(OrganizationUnit entity, Integer expectedVersion) {
        if (entity == null || entity.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        Map<Long, OrganizationUnit> lockedOrganizations =
                lockOrganizations(idSet(entity.getId(), entity.getParentId()), List.of());
        // 旧値はlock後の行で読む（E-2）。
        OrganizationUnit current = lockedOrganizations.get(entity.getId());
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
        boolean relationChanged = recordRelation(entity.getId(), entity.getParentId(),
                entity.getStatus() == null ? current.getStatus() : entity.getStatus(), LocalDate.now());
        if (relationChanged || hierarchyChanged(current, entity)) {
            advanceOrganizations(lockedOrganizations.keySet());
        }
        markScopeChanged();
        return true;
    }

    /**
     * 組織を統合する（計画 §3.4「組織統合（merge）」）。discovery（通常読取）→ 3 X{影響ユーザー}・S{複写する上長} →
     * 6 X{統合元・統合先・子組織と各祖先鎖} → 7 X{統合元所属の要員・会計履歴の要員・影響ユーザーの連携要員} →
     * lock後の読み直し → 8 X{影響ユーザーの所属行} → 書込み（11 会計履歴 → 葉）の順にlockし、組織部分木・actor
     * （所属が変わるユーザー）・engineer（統合元所属の要員と連携要員）のepochを同じtransactionで1回ずつ進める。
     * 管理操作のため自動再試行せず、discoveryとlock後の依存集合が違えば409で止める。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean merge(Long organizationId, Long targetOrganizationId, Integer expectedVersion) {
        if (organizationId == null || targetOrganizationId == null || organizationId.equals(targetOrganizationId)) {
            throw BusinessException.of("error.organization.invalid");
        }
        LocalDate today = LocalDate.now();
        MergeScope discovered = discoverMergeScope(organizationId, today);
        // rank 3: 影響ユーザーはX（削除済みでも所属が残っていれば対象）、所属行へ複写する上長はS（ID昇順）。
        Map<Long, SysUser> affectedUsers = lockUsers(discovered.userIds(), discovered.managerUserIds(), true);
        // rank 6: 統合元・統合先・子組織と各祖先鎖（ID昇順）。子の新しい祖先鎖は統合先の祖先鎖に含まれる。
        List<Long> chainStarts = new ArrayList<>(List.of(organizationId, targetOrganizationId));
        chainStarts.addAll(discovered.childIds());
        Map<Long, OrganizationUnit> lockedOrganizations = lockOrganizations(chainStarts, List.of());
        // source/target とも同一トランザクション内で行ロックしてから検査する。
        // ロック前に読むと、検査通過後に相手が無効化・統合されても気付けない。
        OrganizationUnit current = lockedOrganizations.get(organizationId);
        OrganizationUnit target = lockedOrganizations.get(targetOrganizationId);
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
        // rank 7: 統合元所属の要員・会計履歴の要員・影響ユーザーの連携要員（ID昇順。論理削除済みも返す）。
        Map<Long, Engineer> lockedEngineers = lockEngineers(discovered.engineerIds());
        // lock後に依存集合を読み直す。統合元への新しい所属・子組織・要員はいずれも統合元のX lockを要するため、
        // ここで増えていればdiscoveryとlockの間にcommitされたもので、lockしていない行を書かないよう409で止める。
        MergeScope confirmed = discoverMergeScope(organizationId, today);
        if (!discovered.covers(confirmed)) {
            log.warn("[authorization epoch] 組織統合の依存集合がlockの前後で変わったため中断します: source={}",
                    organizationId);
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        // rank 8: 影響ユーザーの所属行（ユーザーID昇順、各ユーザー内はID昇順）。以降の FOR UPDATE はlock済みの行を読み直す。
        for (Long userId : discovered.userIds()) {
            userOrganizationMapper.selectByUserForUpdate(userId);
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
        // 増分はlock済みの行だけ（新しいlockは取らない）。所属行が変わる影響ユーザーのactor、統合元・統合先・子組織と
        // 各祖先鎖の組織部分木、統合元所属の要員と連携要員のengineerを1回ずつ進める（上長の複写は連携要員で覆う）。
        List<AuthorizationEpochAnchor> anchors = new ArrayList<>();
        for (Long userId : discovered.userIds()) {
            anchors.add(ActorEpochAnchors.of(affectedUsers.get(userId)));
        }
        for (Long lockedOrganizationId : lockedOrganizations.keySet()) {
            anchors.add(AuthorizationEpochAnchor.organization(lockedOrganizationId));
        }
        for (Engineer engineer : lockedEngineers.values()) {
            anchors.add(EngineerEpochAnchors.of(engineer));
        }
        authorizationEpochFence.advanceAll(anchors);
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
     * 呼出元が当該組織行（rank 6）をX lock済みであること（親子履歴は葉）。
     *
     * @return 親子履歴を書いた場合 true（組織部分木epochの増分契機。計画 §2.2）
     */
    private boolean recordRelation(Long organizationId, Long parentId, String status, LocalDate effectiveFrom) {
        if (relationHistoryMapper == null || organizationId == null) {
            return false;
        }
        OrganizationRelationHistory currentRow = relationHistoryMapper.selectCurrent(organizationId);
        if (currentRow != null
                && Objects.equals(currentRow.getParentId(), parentId)
                && Objects.equals(currentRow.getStatus(), status)) {
            return false;
        }
        if (currentRow != null) {
            if (!currentRow.getValidFrom().isBefore(effectiveFrom)) {
                // 同日（またはそれ以降に開始した）版は履歴を増やさず更新する。
                currentRow.setParentId(parentId);
                currentRow.setStatus(status);
                relationHistoryMapper.updateById(currentRow);
                return true;
            }
            relationHistoryMapper.closeCurrent(organizationId, effectiveFrom.minusDays(1));
        }
        relationHistoryMapper.insert(OrganizationRelationHistory.builder()
                .organizationId(organizationId).parentId(parentId).status(status)
                .validFrom(effectiveFrom).validTo(null).build());
        return true;
    }

    /** 無効化。版番号はdiscoveryとして読み、lock・検査・CASは {@link #updateStatus} が同じtransactionで行う。 */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean deactivate(Long organizationId) {
        OrganizationUnit unit = getById(organizationId);
        return unit != null && updateStatus(organizationId, "無効", unit.getVersion());
    }

    /**
     * 状態を版番号CASで変更する。discovery{自身と祖先鎖} → rank 6 X（ID昇順）の後に子組織・在籍者を検査し
     * （子の追加・所属の追加はいずれも同じ組織をX lockするため直列化される）、状態か親子履歴が変わった時だけ
     * 自身と祖先鎖の組織部分木epochを1回ずつ進める。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateStatus(Long organizationId, String status, Integer expectedVersion) {
        if (organizationId == null || expectedVersion == null
                || (!"有効".equals(status) && !"無効".equals(status))) {
            throw BusinessException.of(400, "error.organization.invalid");
        }
        Map<Long, OrganizationUnit> lockedOrganizations = lockOrganizations(List.of(organizationId), List.of());
        OrganizationUnit current = lockedOrganizations.get(organizationId);
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
        boolean relationChanged = recordRelation(organizationId, current.getParentId(), status, today);
        if (relationChanged || !Objects.equals(current.getStatus(), status)) {
            advanceOrganizations(lockedOrganizations.keySet());
        }
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

    /**
     * 未参照の組織を論理削除する。discovery{自身と祖先鎖} → rank 6 X（ID昇順）の後に参照を検査し（子・所属・原価部門・
     * 予算・要員の追加はいずれも同じ組織のX lockまたはFK検査のS lockを要するため直列化される）、削除した時は
     * 自身と祖先鎖の組織部分木epochを1回ずつ進める。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean removeById(Serializable id) {
        Long organizationId = Long.valueOf(id.toString());
        Map<Long, OrganizationUnit> lockedOrganizations = lockOrganizations(List.of(organizationId), List.of());
        if (isReferenced(organizationId)) {
            throw BusinessException.of("error.organization.delete.referenced");
        }
        boolean removed = super.removeById(id);
        if (removed) {
            advanceOrganizations(lockedOrganizations.keySet());
        }
        return removed;
    }

    /**
     * 所属を登録する（計画 §3.4「所属writer（assign）」、§11 F-4）。3 X{本人}・S{上長} → 6 X{対象組織} →
     * 7 X{本人の連携要員} → 8 X{本人の所属行} → insert の順にlockし、本人のactorと連携要員のengineerを進める。
     * 組織行を所属行より先に取り（旧実装は 所属行 → 組織行）、組織統合（3 → 6 → 8）との循環待ちを無くす。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public UserOrganization assignUser(UserOrganization assignment) {
        SysUser user = lockSubject(assignment.getUserId(), assignment.getManagerUserId(), false);
        if (user == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        Long linkedEngineerId = linkedEngineerId(user.getId());
        Map<Long, OrganizationUnit> lockedOrganizations =
                lockOrganizations(List.of(), idSet(assignment.getOrganizationId()));
        OrganizationUnit lockedOrganization = assignment.getOrganizationId() == null ? null
                : lockedOrganizations.get(assignment.getOrganizationId());
        if (lockedOrganization == null) {
            throw BusinessException.of("error.organization.assignment.organizationNotFound");
        }
        Engineer linkedEngineer = lockEngineer(linkedEngineerId);
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
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
        advanceAssignmentChange(user, linkedEngineer);
        markScopeChanged();
        return assignment;
    }

    /**
     * 所属を版番号CASで更新する（{@code manager_user_id} の変更を含む）。discovery{所属行 → 本人・旧組織} →
     * 3 X{本人}・S{新しい上長} → 6 X{旧・新組織} → 7 X{本人の連携要員} → 8 X{本人の所属行} の順にlockし、述語が読む列
     * （組織・上長・主所属・有効期間）が変わった時だけ本人のactorと連携要員のengineerを進める（役職だけでは進めない）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean updateUserOrganization(UserOrganization assignment, Integer expectedVersion) {
        if (assignment == null || assignment.getId() == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        // discovery: 所属行の利用者は更新で変わらない（下で本人の値へ固定する）。
        UserOrganization discovered = userOrganizationMapper.selectById(assignment.getId());
        if (discovered == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        SysUser user = lockSubject(discovered.getUserId(), assignment.getManagerUserId(), true);
        Long linkedEngineerId = linkedEngineerId(discovered.getUserId());
        lockOrganizations(List.of(), idSet(discovered.getOrganizationId(), assignment.getOrganizationId()));
        Engineer linkedEngineer = lockEngineer(linkedEngineerId);
        // 旧値はlock後の行で読む（E-2）。discoveryの後に組織統合が行を付け替えていれば、lockしていない組織を
        // 旧組織として扱わないよう409で止める。
        UserOrganization current = lockedAssignment(discovered.getUserId(), assignment.getId());
        if (current == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        if (!Objects.equals(current.getOrganizationId(), discovered.getOrganizationId())) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        assignment.setUserId(current.getUserId());
        validateAssignment(assignment, assignment.getId());
        assignment.setVersion(expectedVersion);
        if (userOrganizationMapper.updateById(assignment) != 1) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        if (assignmentChanged(current, assignment)) {
            advanceAssignmentChange(user, linkedEngineer);
        }
        markScopeChanged();
        return true;
    }

    /**
     * 所属を異動する（旧行を閉じて新行を追加する）。3 X{本人}・S{上長} → discovery{異動日に重なる所属行の組織} →
     * 6 X{旧・新組織} → 7 X{本人の連携要員} → 8 X{本人の所属行} の順にlockし、本人のactorと連携要員のengineerを進める。
     * 旧実装は 利用者 → 所属行 → 組織行（検査内のlock）の順で、組織統合と逆順だった（§11 F-4 と同型）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public UserOrganization transferUser(UserOrganization assignment, Integer expectedVersion) {
        SysUser user = lockSubject(assignment.getUserId(), assignment.getManagerUserId(), false);
        if (user == null) {
            throw BusinessException.of("error.organization.assignment.userNotFound");
        }
        Long linkedEngineerId = linkedEngineerId(user.getId());
        LocalDate transferDate = assignment.getValidFrom();
        // discovery: 本人の利用者行をX lockした後は、本人の所属行を書く他のwriterは直列化される（全writerが先に本人をlockする）。
        Set<Long> organizationIds = idSet(assignment.getOrganizationId());
        organizationIds.addAll(organizationsOverlapping(userOrganizationMapper.selectList(
                new LambdaQueryWrapper<UserOrganization>().eq(UserOrganization::getUserId, user.getId())), transferDate));
        lockOrganizations(List.of(), organizationIds);
        Engineer linkedEngineer = lockEngineer(linkedEngineerId);
        List<UserOrganization> locked = userOrganizationMapper.selectByUserForUpdate(assignment.getUserId());
        if (!organizationIds.containsAll(organizationsOverlapping(locked, transferDate))) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        validateAssignmentBasics(assignment);
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
        advanceAssignmentChange(user, linkedEngineer);
        markScopeChanged();
        return assignment;
    }

    /**
     * 所属を解除する（終了日を入れて閉じる）。discovery{所属行 → 本人} → 3 X{本人} → 7 X{本人の連携要員} →
     * 8 X{本人の所属行} の順にlockし（対象組織は変えないので rank 6 は取らない）、終了日が変わった時だけ本人のactorと
     * 連携要員のengineerを進める。
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean releaseAssignment(Long assignmentId, LocalDate releaseDate, Integer expectedVersion) {
        if (assignmentId == null || expectedVersion == null) {
            throw BusinessException.of(409, "error.organization.versionConflict");
        }
        UserOrganization discovered = userOrganizationMapper.selectById(assignmentId);
        if (discovered == null) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        SysUser user = lockSubject(discovered.getUserId(), null, true);
        Engineer linkedEngineer = lockEngineer(linkedEngineerId(discovered.getUserId()));
        UserOrganization current = lockedAssignment(discovered.getUserId(), assignmentId);
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
        if (!Objects.equals(current.getValidTo(), date)) {
            advanceAssignmentChange(user, linkedEngineer);
        }
        markScopeChanged();
        return true;
    }

    /**
     * 退職・停止時に本人の有効な所属をすべて閉じ、本人を上長とする他人の所属行から上長を外す。
     *
     * <p>3 X{本人} → 7 X{本人の連携要員} → 8 X{本人の所属行} → 上長解除（他人の所属行）の順にlockし、増分も本メソッドが
     * 自分で行う（W3-15 review P3-6: 呼出元が利用者のstatusを変えない場合でも所属の変化を漏らさない）。所属を閉じた時は
     * 本人のactorと連携要員のengineer、上長を外した時は本人のactorを進める（上長解除の影響は本人の上長経路の述語だけ。
     * 計画 §2.2）。呼出元（利用者の無効化・削除、退社完了）が本人の行を既にlockしていれば同じ行を取り直すだけで待たず、
     * 同じanchorの重複増分はfenceが除く。利用者削除は論理削除の後に呼ぶため、削除済みの利用者行もlockする。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public int closeAssignmentsForUser(Long userId, LocalDate releaseDate) {
        if (userId == null) {
            return 0;
        }
        SysUser user = lockSubject(userId, null, true);
        Engineer linkedEngineer = lockEngineer(linkedEngineerId(userId));
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
        int clearedManagerRows = userOrganizationMapper.clearManager(userId);
        if (closed > 0) {
            advanceAssignmentChange(user, linkedEngineer);
        } else if (clearedManagerRows > 0) {
            authorizationEpochFence.advance(ActorEpochAnchors.of(user));
        }
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

    // ===== NF01 F2: lock・epochの補助（細粒度epoch計画 §2.2・§3.1・§3.5・§4.2） =====

    /**
     * 起点組織とその祖先（{@code m_organization_unit.parent_id} と現行の親子履歴の親の両方）を通常読取で集める
     * （起点を含む）。組織scopeの読み手は親子履歴を、他の画面は {@code parent_id} を読むため、両方の祖先を対象にする
     * （食い違う場合は過剰に進めるだけで、漏れは起きない）。
     */
    private Set<Long> organizationChain(Long startId) {
        Set<Long> chain = new TreeSet<>();
        ArrayDeque<Long> pending = new ArrayDeque<>();
        if (startId != null) {
            pending.add(startId);
        }
        while (!pending.isEmpty()) {
            Long id = pending.poll();
            if (!chain.add(id)) {
                continue;
            }
            OrganizationUnit unit = getById(id);
            if (unit != null && unit.getParentId() != null) {
                pending.add(unit.getParentId());
            }
            OrganizationRelationHistory relation = relationHistoryMapper == null ? null
                    : relationHistoryMapper.selectCurrent(id);
            if (relation != null && relation.getParentId() != null) {
                pending.add(relation.getParentId());
            }
        }
        return chain;
    }

    /**
     * rank 6: 起点組織の祖先鎖（自身を含む）と単独の組織を、ID昇順にX lockする（計画 §3.5 規則3）。lock後に祖先鎖を
     * 読み直し、lockしていない組織が鎖に現れた（discoveryとlockの間に親子が変わった）場合は、途中でlockを足さず
     * 409で止める（規則1。管理操作のため自動再試行しない）。lock済みの組織の親子は、その組織をX lockするwriterしか
     * 変えないため、読み直しの結果はcommitまで安定する。
     *
     * @return lockした組織行（ID → lock後の現在値）。行が無い・論理削除済みのIDは含めない
     */
    private Map<Long, OrganizationUnit> lockOrganizations(Collection<Long> chainStarts, Collection<Long> singles) {
        Set<Long> discovered = new TreeSet<>();
        for (Long start : chainStarts) {
            discovered.addAll(organizationChain(start));
        }
        for (Long id : singles) {
            if (id != null) {
                discovered.add(id);
            }
        }
        Map<Long, OrganizationUnit> locked = new TreeMap<>();
        for (Long id : discovered) {
            OrganizationUnit row = baseMapper.selectByIdForUpdate(id);
            if (row != null) {
                locked.put(id, row);
            }
        }
        for (Long start : chainStarts) {
            if (!discovered.containsAll(organizationChain(start))) {
                log.warn("[authorization epoch] 組織の祖先鎖がlockの前後で変わったため中断します: start={}", start);
                throw BusinessException.of(409, "error.organization.versionConflict");
            }
        }
        return locked;
    }

    /**
     * rank 3: 本人の利用者行をX lockし、所属行に設定する上長の利用者行をS lockする（ID昇順。同じIDならXだけ）。
     * 上長を先にlockしておくのは、所属行のinsert・更新でFK検査が上長行に付けるS lockを、所属行（rank 8）の後に
     * 新しく取らないため（上長本人の所属writerとの循環待ちを避ける）。
     *
     * @param includeDeleted 論理削除済みの本人行もlockする（利用者削除の後段の所属close等）
     * @return lock後の本人の行（無ければ {@code null}）
     */
    private SysUser lockSubject(Long userId, Long managerUserId, boolean includeDeleted) {
        if (userId == null) {
            return null;
        }
        return lockUsers(idSet(userId), idSet(managerUserId), includeDeleted).get(userId);
    }

    /**
     * rank 3: 利用者行をID昇順にlockする（{@code exclusiveIds} はX、それ以外の {@code sharedIds} はS）。
     *
     * @return X lockした利用者行（ID → 行）。行が無いIDは含めない
     */
    private Map<Long, SysUser> lockUsers(Collection<Long> exclusiveIds, Collection<Long> sharedIds,
                                         boolean includeDeleted) {
        Set<Long> exclusive = new TreeSet<>();
        exclusiveIds.stream().filter(Objects::nonNull).forEach(exclusive::add);
        Set<Long> all = new TreeSet<>(exclusive);
        sharedIds.stream().filter(Objects::nonNull).forEach(all::add);
        Map<Long, SysUser> locked = new TreeMap<>();
        for (Long id : all) {
            if (!exclusive.contains(id)) {
                actorShareLock.lockActive(id, null);
                continue;
            }
            SysUser row = includeDeleted ? sysUserMapper.selectByIdForUpdateIncludingDeleted(id)
                    : sysUserMapper.selectByIdForUpdate(id);
            if (row != null) {
                locked.put(id, row);
            }
        }
        return locked;
    }

    /** 本人の連携要員のID（紐付けのwriterは連携ユーザー行をlockするため、本人行のX lock下で安定する）。 */
    private Long linkedEngineerId(Long userId) {
        if (userId == null) {
            return null;
        }
        EngineerAccountLink link = engineerAccountLinkMapper.selectByUserId(userId);
        return link == null ? null : link.getEngineerId();
    }

    /**
     * rank 7: 要員行をX lockする（論理削除済みも返す。行が無ければ {@code null}）。要員mapperを配線していない構成では
     * 連携要員の世代を進められないため、fail-closedで409にする。
     */
    private Engineer lockEngineer(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        if (engineerMapper == null) {
            log.warn("[authorization epoch] 要員mapperが無いため要員行をlockできません: engineerId={}", engineerId);
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return engineerMapper.selectScopeAnchorForUpdate(engineerId);
    }

    /** rank 7: 要員行をID昇順にX lockする。 */
    private Map<Long, Engineer> lockEngineers(Collection<Long> engineerIds) {
        Map<Long, Engineer> locked = new TreeMap<>();
        for (Long id : new TreeSet<>(engineerIds)) {
            Engineer row = lockEngineer(id);
            if (row != null) {
                locked.put(id, row);
            }
        }
        return locked;
    }

    /** rank 8: 本人の所属行をID昇順にX lockし、対象の行をlock後の現在値で返す（無ければ {@code null}）。 */
    private UserOrganization lockedAssignment(Long userId, Long assignmentId) {
        return userOrganizationMapper.selectByUserForUpdate(userId).stream()
                .filter(row -> Objects.equals(row.getId(), assignmentId))
                .findFirst().orElse(null);
    }

    /** lockした組織の組織部分木epochを、lock順（ID昇順）に1回ずつ進める。 */
    private void advanceOrganizations(Collection<Long> organizationIds) {
        if (organizationIds.isEmpty()) {
            return;
        }
        authorizationEpochFence.advanceAll(organizationIds.stream()
                .map(AuthorizationEpochAnchor::organization)
                .toList());
    }

    /**
     * 所属行の変化を、本人のactor epochと本人の連携要員のengineer epochへ反映する（計画 §2.2: 連携要員の組織帰属と
     * 上長経路は連携ユーザーの所属行を読む）。tenantはlockした行の値を使う。
     */
    private void advanceAssignmentChange(SysUser lockedUser, Engineer lockedLinkedEngineer) {
        List<AuthorizationEpochAnchor> anchors = new ArrayList<>();
        anchors.add(ActorEpochAnchors.of(lockedUser));
        if (lockedLinkedEngineer != null) {
            anchors.add(EngineerEpochAnchors.of(lockedLinkedEngineer));
        }
        authorizationEpochFence.advanceAll(anchors);
    }

    /** 組織部分木の述語が読む列（親・状態・有効期間・統合先）が変わるか。{@code null} は更新しない（not_null）。 */
    private static boolean hierarchyChanged(OrganizationUnit current, OrganizationUnit requested) {
        return changed(current.getParentId(), requested.getParentId())
                || changed(current.getStatus(), requested.getStatus())
                || changed(current.getValidFrom(), requested.getValidFrom())
                || changed(current.getValidTo(), requested.getValidTo())
                || changed(current.getMergedInto(), requested.getMergedInto());
    }

    /** scope述語が読む所属行の列（組織・上長・主所属・有効期間）が変わるか。役職は読まない。 */
    private static boolean assignmentChanged(UserOrganization current, UserOrganization requested) {
        return changed(current.getOrganizationId(), requested.getOrganizationId())
                || changed(current.getManagerUserId(), requested.getManagerUserId())
                || changed(current.getPrimaryFlag(), requested.getPrimaryFlag())
                || changed(current.getValidFrom(), requested.getValidFrom())
                || changed(current.getValidTo(), requested.getValidTo());
    }

    private static boolean changed(Object current, Object requested) {
        return requested != null && !Objects.equals(current, requested);
    }

    /** 指定日に重なる所属行の組織ID（日付が無ければ空）。 */
    private Set<Long> organizationsOverlapping(List<UserOrganization> rows, LocalDate date) {
        Set<Long> result = new TreeSet<>();
        if (date == null) {
            return result;
        }
        for (UserOrganization row : rows) {
            if (row.getValidFrom() != null && row.getOrganizationId() != null
                    && overlaps(row.getValidFrom(), row.getValidTo(), date, date)) {
                result.add(row.getOrganizationId());
            }
        }
        return result;
    }

    /** {@code null} を除いた可変のID集合（昇順）。 */
    private static Set<Long> idSet(Long... values) {
        Set<Long> result = new TreeSet<>();
        for (Long value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 組織統合の依存集合（通常読取）。統合元の未終了の所属を持つ影響ユーザー、その所属行の上長、子組織、統合元に
     * 所属する要員・統合元を現行の会計履歴に持つ要員・影響ユーザーの連携要員。
     */
    private MergeScope discoverMergeScope(Long organizationId, LocalDate mergeDate) {
        Set<Long> userIds = new TreeSet<>();
        for (UserOrganization row : userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getOrganizationId, organizationId)
                .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, mergeDate)))) {
            if (row.getUserId() != null) {
                userIds.add(row.getUserId());
            }
        }
        Set<Long> managerUserIds = new TreeSet<>();
        Set<Long> engineerIds = new TreeSet<>();
        if (!userIds.isEmpty()) {
            for (UserOrganization row : userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                    .in(UserOrganization::getUserId, userIds))) {
                if (row.getManagerUserId() != null) {
                    managerUserIds.add(row.getManagerUserId());
                }
            }
            for (Long userId : userIds) {
                Long linked = linkedEngineerId(userId);
                if (linked != null) {
                    engineerIds.add(linked);
                }
            }
        }
        Set<Long> childIds = new TreeSet<>();
        for (OrganizationUnit child : list(new LambdaQueryWrapper<OrganizationUnit>()
                .eq(OrganizationUnit::getParentId, organizationId))) {
            childIds.add(child.getId());
        }
        if (engineerMapper != null) {
            for (Engineer engineer : engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                    .select(Engineer::getId)
                    .eq(Engineer::getOrganizationId, organizationId))) {
                engineerIds.add(engineer.getId());
            }
        }
        if (engineerAccountingHistoryMapper != null) {
            for (com.ses.entity.EngineerAccountingHistory history
                    : engineerAccountingHistoryMapper.selectCurrentByOrganizationId(organizationId)) {
                if (history.getEngineerId() != null) {
                    engineerIds.add(history.getEngineerId());
                }
            }
        }
        return new MergeScope(userIds, managerUserIds, childIds, engineerIds);
    }

    /** 組織統合の依存集合（いずれもID昇順）。 */
    private record MergeScope(Set<Long> userIds, Set<Long> managerUserIds, Set<Long> childIds,
                              Set<Long> engineerIds) {

        /** lock後に読み直した集合が、lockした集合に含まれるか（上長はX・Sのどちらでlockしていてもよい）。 */
        boolean covers(MergeScope after) {
            Set<Long> lockedUsers = new TreeSet<>(userIds);
            lockedUsers.addAll(managerUserIds);
            return userIds.containsAll(after.userIds())
                    && lockedUsers.containsAll(after.managerUserIds())
                    && childIds.containsAll(after.childIds())
                    && engineerIds.containsAll(after.engineerIds());
        }
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
