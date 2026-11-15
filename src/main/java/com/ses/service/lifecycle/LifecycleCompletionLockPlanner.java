package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.ExternalAccountReference;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.ExternalAccountReferenceMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.LockKey;
import com.ses.service.lifecycle.evidence.LifecycleLockPlan.Rank;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 案件完了（{@code LifecycleCaseServiceImpl.completeCase}）が書く行を、lockを取らずに列挙して1つのlock planにする
 * （NF01細粒度epoch計画 §3.4「completeCase（RESIGNATION、W3-16b）」、§3.5、§4.2）。
 *
 * <p>planの並び（{@link LifecycleLockPlan.Rank} の順）:</p>
 * <ol>
 *   <li>順3 {@code sys_user}: 操作者はS、連携ユーザーはX（ID昇順。同じIDは拒否する）。連携ユーザーを要員行より先に取り、
 *       利用者・所属のwriter（3 X → 7 X）・紐付け解除（3 S → 7 X）・要員削除（3 X → 7 X）・変更申請の承認
 *       （2 X → 3 X → 7 X）と同じ向きにする（旧実装は 4 X → 7 X → 3 X で、これらと循環待ち（1213）になり得た）。</li>
 *   <li>順4 案件X → 順5 全タスクX（案件X保持下、ID昇順）。</li>
 *   <li>退社案件だけ: 順7 要員X → 順8 連携ユーザーの所属行と、連携ユーザーを上長とする他人の所属行（上長解除の対象）→
 *       順9 紐付け → 順10 現任の担当営業割当 → 順14 未失効の外部アカウント（全法人）。</li>
 * </ol>
 *
 * <p>呼出側はこのplanを {@code LifecycleLockExecutor} で1回だけ取り、取得後に同じメソッドで列挙し直して
 * {@link LifecycleLockPlan#requireSameDependencies} で比べる（不一致は途中でlockを足さず409。§3.5 規則1）。
 * 取得後の書込みは取得済みの行と葉（session・event・通知・監査）だけで、新しいrank行のlockを取らない。
 * 列挙は自動処理（{@link ResignationGateChecker#applyAutomaticScopeActions}）と完了時の失効要求登録
 * （{@code ExternalAccountRevocationOutbox.enqueueForResignation}）が書く行と同じ照会で行う。</p>
 *
 * <p>順12（portal link）・資産貸与・license・経費は読取だけでlockしない（§3.4）。portal linkのwriterは要員行をX lockする
 * ため、要員行のX保持下で集合が安定する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleCompletionLockPlanner {

    private static final String RESIGNATION = "RESIGNATION";

    private final LifecycleTaskMapper taskMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final ExternalAccountReferenceMapper externalAccountReferenceMapper;

    /**
     * 完了処理の依存先を列挙する（通常読取。READ_COMMITTEDの完了transaction内で呼ぶ）。
     *
     * @param lcCase     完了する案件（tenant照合済み）
     * @param operatorId 操作者の利用者ID
     * @param tenantId   操作者のtenant（DBの値）
     * @throws BusinessException 400 操作者自身が退社要員の連携ユーザーである場合（業務規則。他の自分自身への操作の拒否と同じ
     *                           400にし、画面に案内文言を出す）、409 tenantを読めない所属行がある場合
     */
    public LifecycleLockPlan plan(LifecycleCase lcCase, Long operatorId, String tenantId) {
        Objects.requireNonNull(lcCase, "lcCase");
        List<LockKey> keys = new ArrayList<>();
        keys.add(LockKey.shared(Rank.SYS_USER, tenantId, operatorId));
        keys.add(LockKey.exclusive(Rank.LIFECYCLE_CASE, tenantId, lcCase.getId()));
        for (LifecycleTask task : taskMapper.selectByCaseIdAndTenant(lcCase.getId(), tenantId)) {
            if (task != null && task.getId() != null) {
                keys.add(LockKey.exclusive(Rank.LIFECYCLE_TASK, tenantId, task.getId()));
            }
        }
        if (RESIGNATION.equals(lcCase.getLifecycleType())) {
            addResignationTargets(keys, lcCase.getEngineerId(), operatorId, tenantId);
        }
        return LifecycleLockPlan.from(keys);
    }

    private void addResignationTargets(List<LockKey> keys, Long engineerId, Long operatorId, String tenantId) {
        keys.add(LockKey.exclusive(Rank.ENGINEER, tenantId, engineerId));
        // 自動処理と同じ照会（連携ユーザー・要員とも同じtenantで未削除の紐付け）。
        EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerIdAndTenant(engineerId, tenantId);
        Long linkedUserId = link == null ? null : link.getSysUserId();
        if (linkedUserId != null) {
            if (linkedUserId.equals(operatorId)) {
                // 同じ行をS（操作者）とX（停止する連携ユーザー）で取ることになり、完了操作の途中で自分のアカウントを停止する。
                // 権限不足ではなく業務規則の拒否のため400で返す（画面の共通処理は403の本文を固定文言に置き換えるため、
                // 「別の管理者またはHRが完了してください」の案内を表示できるようにする。W3-16b review P3-2）。
                log.info("操作者自身に連携された要員の退社案件の完了を拒否しました: engineerId={}", engineerId);
                throw BusinessException.of(400, "error.lifecycle.selfResignationCompletion");
            }
            keys.add(LockKey.exclusive(Rank.SYS_USER, tenantId, linkedUserId));
            // 所属の一括終了（closeAssignmentsForUser）が閉じる本人の行と、上長解除（clearManager）が書く他人の行。
            for (UserOrganization row : userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                    .eq(UserOrganization::getUserId, linkedUserId))) {
                keys.add(LockKey.exclusive(Rank.USER_ORGANIZATION, partitionOf(row), row.getId()));
            }
            for (UserOrganization row : userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                    .eq(UserOrganization::getManagerUserId, linkedUserId))) {
                keys.add(LockKey.exclusive(Rank.USER_ORGANIZATION, partitionOf(row), row.getId()));
            }
            keys.add(LockKey.exclusive(Rank.ENGINEER_ACCOUNT_LINK, tenantId, link.getId()));
        }
        // 担当営業の一括解除（releaseAllByEngineerId）が閉じる現任の割当。
        for (EngineerSales row : engineerSalesMapper.selectList(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, engineerId)
                .isNull(EngineerSales::getReleasedAt))) {
            keys.add(LockKey.exclusive(Rank.ENGINEER_SALES, "", row.getId()));
        }
        // 完了時の失効要求登録（enqueueForResignation）が書く未失効の外部アカウント（全法人）。
        for (ExternalAccountReference row : externalAccountReferenceMapper.selectUnrevokedForEngineer(tenantId, engineerId)) {
            keys.add(LockKey.exclusive(Rank.EXTERNAL_ACCOUNT_REFERENCE, tenantId, row.getId()));
        }
    }

    /** 所属行のpartitionは行自身のtenant（DBの値）。読めない行は所属の終了を確定できないためfail-closedにする。 */
    private static String partitionOf(UserOrganization row) {
        if (row.getTenantId() == null || row.getTenantId().isBlank()) {
            log.warn("[authorization epoch] tenantを読めない所属行があるため退社完了を中断します: assignmentId={}", row.getId());
            throw BusinessException.of(409, AuthorizationEpochFence.EPOCH_UNAVAILABLE_KEY);
        }
        return row.getTenantId();
    }
}
