package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.dto.lifecycle.ResignationGateResultDto.GateItemResult;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.AssetOffboardingService;
import com.ses.service.OrganizationService;
import com.ses.service.portal.PortalSessionService;
import com.ses.service.security.PersistentSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 退社ゲート検証コンポーネント
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResignationGateChecker {

    private final SysUserMapper sysUserMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final EngineerSalesService engineerSalesService;
    private final com.ses.mapper.EngineerSalesMapper engineerSalesMapper;
    private final com.ses.mapper.PortalUserEngineerLinkMapper portalUserEngineerLinkMapper;
    private final com.ses.mapper.ContractMapper contractMapper;
    private final OrganizationService organizationService;
    private final PersistentSessionService persistentSessionService;
    private final PortalSessionService portalSessionService;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final LifecycleTaskMapper lifecycleTaskMapper;
    private final AssetOffboardingService assetOffboardingService;

    /**
     * 退社ゲートの全項目を検証する。
     */
    public ResignationGateResultDto evaluate(LifecycleCase lcCase, Engineer engineer) {
        List<GateItemResult> items = new ArrayList<>();
        boolean allPassed = true;

        Long engineerId = engineer.getId();
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        Long linkedUserId = link != null ? link.getSysUserId() : null;
        SysUser linkedUser = linkedUserId != null ? sysUserMapper.selectById(linkedUserId) : null;

        // 1. 内部アカウント無効化 (USER_DEACTIVATION - 自動実行)
        // アカウント未連携の場合は対象なし（自動実行不要）。連携済みの場合は現状 status==0 かどうかを確認。
        boolean accountDeactivated;
        String accountMsg;
        if (linkedUser == null) {
            // アカウント未連携: 内部ログインアカウントが存在しないため無効化対象なし
            accountDeactivated = true;
            accountMsg = "ログインアカウント未連携のため無効化対象なし";
        } else if (linkedUser.getStatus() != null && linkedUser.getStatus() == 0) {
            accountDeactivated = true;
            accountMsg = "ログインアカウントは既に無効化済みです (status=0)";
        } else {
            // まだ有効(status=1等) — executeAutomaticGateActions で自動実行される
            accountDeactivated = true;
            accountMsg = "案件完了確定時にログインアカウントを自動的に無効化します (現在: status=" + linkedUser.getStatus() + ")";
        }
        items.add(GateItemResult.builder()
                .code("USER_DEACTIVATION")
                .name("ログインアカウント無効化")
                .passed(accountDeactivated)
                .autoExecutable(true)
                .message(accountMsg)
                .build());

        // 2. Webセッション失効 (SESSION_REVOCATION - 自動実行)
        // executeAutomaticGateActions で必ず実行される。未連携の場合はセッションなし。
        // ポータルsessionの失効対象は紐付け済みのポータルユーザーだけ（PORTAL_ACCOUNT_LINK）。
        String sessionMsg = linkedUser != null
                ? "案件完了確定時に内部の有効セッションを強制失効します（ポータルは紐付け済みユーザーのみ）"
                : "ログインアカウント未連携のため内部セッション失効対象なし";
        items.add(GateItemResult.builder()
                .code("SESSION_REVOCATION")
                .name("全セッション強制失効")
                .passed(true)
                .autoExecutable(true)
                .message(sessionMsg)
                .build());

        // 3. 要員ポータル連携解除または無効化 (PORTAL_UNLINK - 自動実行)
        // アカウント連携が存在する場合は executeAutomaticGateActions で解除される。
        String portalMsg = link != null
                ? "案件完了確定時に要員ポータル連携を解除します"
                : "ポータル連携なし（連携解除対象外）";
        items.add(GateItemResult.builder()
                .code("PORTAL_UNLINK")
                .name("要員ポータル連携解除・無効化")
                .passed(true)
                .autoExecutable(true)
                .message(portalMsg)
                .build());

        // 3b. ポータルアカウントの紐付け確認 (PORTAL_ACCOUNT_LINK - 自動実行 / NF01 N1-4・決定 Q-10)
        // 自動処理（ポータルsession失効）の対象は管理者が登録した紐付けだけ。emailが一致しても紐付けの無い
        // ポータルユーザーは自動処理せず「要確認」として列挙する（ブロックはしない）。紐付けのtenant不整合は
        // 誤処理・処理漏れを避けるためFAILとし、完了確定も409で止まる。
        PortalLinkTargets portalTargets = portalLinkTargets(lcCase, engineerId, linkedUser);
        if (portalTargets.mismatchedLinks() > 0) allPassed = false;
        items.add(GateItemResult.builder()
                .code("PORTAL_ACCOUNT_LINK")
                .name("ポータルアカウントの紐付け確認")
                .passed(portalTargets.mismatchedLinks() == 0)
                .autoExecutable(true)
                .pendingConfirmation(!portalTargets.pendingPortalUserIds().isEmpty())
                .pendingPortalUserIds(portalTargets.pendingPortalUserIds())
                .message(portalLinkMessage(portalTargets))
                .build());

        // 4. 担当営業割当の解除・引継ぎ (SALES_RELEASE - 自動実行)
        // executeAutomaticGateActions で有効な割当が全件解除される。
        List<EngineerSales> activeSalesForCheck = engineerSalesMapper.selectList(
                new LambdaQueryWrapper<EngineerSales>()
                        .eq(EngineerSales::getEngineerId, engineerId)
                        .isNull(EngineerSales::getReleasedAt));
        String salesMsg = activeSalesForCheck.isEmpty()
                ? "有効な担当営業割当なし（解除対象外）"
                : "案件完了確定時に " + activeSalesForCheck.size() + " 件の担当営業割当を自動的に解除します";
        items.add(GateItemResult.builder()
                .code("SALES_RELEASE")
                .name("担当営業の引継ぎ・割当解除")
                .passed(true)
                .autoExecutable(true)
                .message(salesMsg)
                .build());

        // 5. 組織所属の終了 (ORG_ASSIGNMENT_CLOSE - 自動実行)
        // executeAutomaticGateActions で closeAssignmentsForUser が実行される。
        String orgMsg = linkedUser != null
                ? "案件完了確定時に有効な組織所属を自動的に閉鎖します"
                : "ログインアカウント未連携のため組織所属閉鎖対象なし";
        items.add(GateItemResult.builder()
                .code("ORG_ASSIGNMENT_CLOSE")
                .name("組織所属の終了")
                .passed(true)
                .autoExecutable(true)
                .message(orgMsg)
                .build());

        // 6. 貸与資産の返却 (ASSET_RETURN)
        LifecycleTask assetTask = findTaskByCode(lcCase.getId(), "RESIGN_ASSET_RETURN");
        com.ses.dto.asset.OffboardingClearanceResultDto offboarding =
                assetOffboardingService.checkOffboardingClearance(
                        engineerId, lcCase.getId(), assetTask != null ? assetTask.getId() : null);
        boolean blockersClear = offboarding.getUnreturnedAssetCount() == 0
                && offboarding.getUnrevokedAccountCount() == 0
                && offboarding.getUnreleasedLicenseCount() == 0;
        boolean assetReturned = false;
        boolean assetWaived = false;
        String assetMsg;
        if (assetTask != null) {
            if ("COMPLETED".equals(assetTask.getStatus())) {
                assetReturned = blockersClear;
                assetMsg = blockersClear
                        ? "貸与物返却確認済み（3大blockerなし）"
                        : "貸与物返却タスクは完了していますが、未返却・未失効・未解放のblockerが残存しています";
            } else if ("WAIVED".equals(assetTask.getStatus()) && offboarding.isWaived()) {
                assetReturned = true;
                assetWaived = true;
                assetMsg = blockersClear
                        ? "例外承認により返却免除済み（3大blockerなし）"
                        : "承認済み例外により3大blockerを免除済み";
            } else {
                assetReturned = false;
                assetMsg = "貸与資産返却タスク（PC、スマートフォン、入館証等）が未完了、または3大blockerが残存しています";
            }
        } else if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            assetReturned = false;
            assetMsg = "退社案件に必須の貸与資産返却タスク(RESIGN_ASSET_RETURN)が定義されていません";
        } else {
            assetReturned = blockersClear;
            assetMsg = blockersClear ? "貸与・アカウント・ライセンスのblockerなし" : "退社blockerが残存しています";
        }
        if (!assetReturned) allPassed = false;
        items.add(GateItemResult.builder()
                .code("ASSET_RETURN")
                .name("貸与資産・セキュリティカード返却")
                .passed(assetReturned)
                .autoExecutable(false)
                .waived(assetWaived)
                .approvalRequestId(assetTask != null ? assetTask.getApprovalRequestId() : null)
                .message(assetMsg)
                .build());

        // 7. 未精算経費の確認 (UNSETTLED_EXPENSE)
        boolean expenseClean = true;
        String expenseMsg = "未精算経費なし";
        Long unsettledCount = expenseRequestMapper.selectCount(new LambdaQueryWrapper<ExpenseRequest>()
                .eq(ExpenseRequest::getEngineerId, engineerId)
                .in(ExpenseRequest::getStatus, List.of("DRAFT", "REQUESTED", "APPROVED", "ACCOUNTING_SYNCED")));
        if (unsettledCount != null && unsettledCount > 0) {
            LifecycleTask expTask = findTaskByCode(lcCase.getId(), "RESIGN_EXPENSE_SETTLE");
            boolean expWaived = expTask != null && "WAIVED".equals(expTask.getStatus());
            if (!expWaived) {
                expenseClean = false;
                expenseMsg = "未精算の経費申請が " + unsettledCount + " 件残存しています。支払または取消を完了してください。";
            } else {
                expenseClean = true;
                expenseMsg = "未精算経費について例外承認により免除済み";
            }
        }
        if (!expenseClean) allPassed = false;
        items.add(GateItemResult.builder()
                .code("UNSETTLED_EXPENSE")
                .name("未精算経費・立替金の精算確認")
                .passed(expenseClean)
                .autoExecutable(false)
                .message(expenseMsg)
                .build());

        // 8. 法定文書・誓約書保管確認 (DOCUMENT_RETENTION)
        LifecycleTask docTask = findTaskByCode(lcCase.getId(), "RESIGN_DOC_RETENTION");
        boolean docSaved = true;
        boolean docWaived = false;
        String docMsg = "退社関連文書（退職届・誓約書）保管確認済み";
        if (docTask != null) {
            if ("COMPLETED".equals(docTask.getStatus())) {
                docSaved = true;
            } else if ("WAIVED".equals(docTask.getStatus())) {
                docSaved = true;
                docWaived = true;
                docMsg = "例外承認により文書確認免除済み";
            } else {
                docSaved = false;
                docMsg = "退職届・秘密保持誓約書等の保管タスクが未完了です";
            }
        } else if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            docSaved = false;
            docMsg = "退社案件に必須の文書保管タスク(RESIGN_DOC_RETENTION)が定義されていません";
        }
        if (!docSaved) allPassed = false;
        items.add(GateItemResult.builder()
                .code("DOCUMENT_RETENTION")
                .name("退職関連文書・誓約書の保管確認")
                .passed(docSaved)
                .autoExecutable(false)
                .waived(docWaived)
                .approvalRequestId(docTask != null ? docTask.getApprovalRequestId() : null)
                .message(docMsg)
                .build());

        // 9. 稼働中契約の終了確認 (ACTIVE_CONTRACT)
        long activeContractCount = contractMapper.selectCountForTenant(new LambdaQueryWrapper<com.ses.entity.Contract>()
                .eq(com.ses.entity.Contract::getEngineerId, engineerId)
                .eq(com.ses.entity.Contract::getStatus, "稼動中"),
                com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext());
        boolean noActiveContracts = activeContractCount == 0;
        String contractMsg = noActiveContracts
                ? "稼働中の契約なし"
                : "稼働中の契約が " + activeContractCount + " 件残存しています。契約を終了または解約してから退社手続きを完了してください。";
        if (!noActiveContracts) allPassed = false;
        items.add(GateItemResult.builder()
                .code("ACTIVE_CONTRACT")
                .name("稼働中契約の終了確認")
                .passed(noActiveContracts)
                .autoExecutable(false)
                .message(contractMsg)
                .build());

        return ResignationGateResultDto.builder()
                .passed(allPassed)
                .summary(allPassed ? "退社ゲート全項目PASS。案件を完了可能です。" : "退社ゲートに未充足の項目が存在します。")
                .items(items)
                .build();
    }

    /**
     * 案件完了確定時に退社ゲートの自動実行処理（アカウント無効化・セッション強制失効・組織閉鎖・担当営業解除・ポータル連携解除）を行う。
     * Fail-Closed: 例外発生時は例外を送出し、呼び出し元のトランザクションをロールバックする。
     *
     * <p>NF01 batch 0: 連携ユーザーは案件tenant限定でlocking readし、停止は status 列だけを更新する
     * （非ロック読取した全列を updateById で書き戻すと、並行して確定した他列の更新を古い値で上書きする）。
     * 担当営業の解除は更新日時の記録とDataScope invalidationを持つ
     * {@link EngineerSalesService#releaseAllByEngineerId(Long, LocalDate)} を通し、解除日には退社基準日を残す。</p>
     *
     * <p>NF01 N1-4（決定 B-NF01-8・Q-10）: ポータルsessionを失効させるのは、管理者が登録した紐付け
     * （V188 t_portal_user_engineer_link）があり、紐付け・ポータル組織とも案件tenantに属するポータルユーザーだけ。
     * emailでは照合しない（同じemailの無関係なユーザー・他tenantのユーザーを処理しない）。紐付けの無いポータルユーザーは
     * {@link #evaluate} が「要確認」として列挙する。紐付けにtenant不整合があれば、どの撤権より先に409で止める。</p>
     */
    public void executeAutomaticGateActions(LifecycleCase lcCase, Engineer engineer) {
        Long engineerId = engineer.getId();
        LocalDate anchorDate = lcCase.getAnchorDate() != null ? lcCase.getAnchorDate() : LocalDate.now();
        String tenantId = lcCase.getTenantId() == null || lcCase.getTenantId().isBlank()
                ? null : lcCase.getTenantId().trim();
        if (tenantId == null) {
            throw BusinessException.of(409, "error.lifecycle.resignationGateFailed");
        }
        List<PortalUserEngineerLink> portalLinks = linkedPortalUsersFailClosed(engineerId, tenantId);

        // 1. ユーザーアカウント無効化 & 組織所属閉鎖 & セッション失効 & ポータル連携解除
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        if (link != null && link.getSysUserId() != null) {
            Long userId = link.getSysUserId();
            SysUser user = lockLinkedUserInTenant(userId, tenantId);
            if (user != null && (user.getStatus() == null || user.getStatus() != 0)) {
                int updated = sysUserMapper.update(null, new UpdateWrapper<SysUser>()
                        .eq("id", userId)
                        .eq("tenant_id", tenantId)
                        .set("status", 0)
                        .set("updated_at", LocalDateTime.now()));
                if (updated != 1) {
                    throw BusinessException.of(409, "error.common.optimisticLock");
                }
                log.info("Deactivated user {} due to resignation case completion", userId);
            }

            int closedCount = organizationService.closeAssignmentsForUser(userId, anchorDate);
            log.info("Closed {} organization assignments for user {}", closedCount, userId);

            // 内部セッション強制失効 (Fail-Closed)
            persistentSessionService.revokeAllForUser(userId, "退社案件完了によるセッション強制失効");
            log.info("Revoked all persistent sessions for user {}", userId);

            // ポータル連携解除
            engineerAccountLinkService.unlinkByEngineerId(engineerId);
            log.info("Unlinked engineer account link for engineer {}", engineerId);
        }

        // 紐付け済みポータルユーザーのセッション失効 (Fail-Closed)。内部アカウント連携の有無とは独立に行う。
        for (PortalUserEngineerLink portalLink : portalLinks) {
            portalSessionService.revokeAllForUser(portalLink.getPortalUserId(), "退社案件完了によるポータルセッション強制失効");
            log.info("退社案件完了により紐付け済みポータルユーザー {} のセッションを失効しました", portalLink.getPortalUserId());
        }

        // 2. 主担当営業および割当の解除。解除日は組織所属の終了日と同じ退社基準日とし、
        //    更新日時の記録とDataScope invalidationを持つservice経由で行う。
        engineerSalesService.releaseAllByEngineerId(engineerId, anchorDate);
        log.info("退社案件完了により要員 {} の現任担当営業割当を基準日 {} で解除しました", engineerId, anchorDate);
    }

    /**
     * 連携ユーザーを案件tenant内でlocking readする。
     * tenant内に無く他tenantには存在する場合は、越境更新も無効化漏れも起こさないようfail-closedにする。
     * どのtenantにも有効行が無い（論理削除済み等）場合は従来どおり無効化対象なしとして扱う。
     */
    private SysUser lockLinkedUserInTenant(Long userId, String tenantId) {
        SysUser user = sysUserMapper.selectByIdAndTenantForUpdate(userId, tenantId);
        if (user == null && sysUserMapper.selectById(userId) != null) {
            throw BusinessException.of(409, "error.lifecycle.resignationGateFailed");
        }
        return user;
    }

    /**
     * 自動処理の対象にする紐付け済みポータルユーザーを返す。tenant不整合の現行紐付けが1件でもあれば、
     * 誤撤権・撤権漏れを避けるため409で中断する（NF01 N1-4。batch 0 L6のemail照合を置き換える）。
     */
    private List<PortalUserEngineerLink> linkedPortalUsersFailClosed(Long engineerId, String tenantId) {
        Long mismatched = portalUserEngineerLinkMapper.countActiveScopeMismatchForEngineer(engineerId, tenantId);
        if (mismatched != null && mismatched > 0) {
            throw BusinessException.of(409, "error.lifecycle.resignationGateFailed");
        }
        List<PortalUserEngineerLink> links =
                portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(engineerId, tenantId);
        return links == null ? List.of() : links;
    }

    /**
     * 退社ゲートに表示するポータルアカウントの状況。紐付け済み（自動処理の対象）、要確認（同じtenantで
     * 内部アカウントのemailが一致するが、どの要員とも紐付いていないポータルユーザー）、tenant不整合の紐付けの件数。
     * 要確認の照合は表示のためだけに行い、自動処理には使わない。内部アカウントが案件tenantに無い場合は照合しない。
     */
    private PortalLinkTargets portalLinkTargets(LifecycleCase lcCase, Long engineerId, SysUser linkedUser) {
        String tenantId = lcCase.getTenantId() == null || lcCase.getTenantId().isBlank()
                ? null : lcCase.getTenantId().trim();
        if (tenantId == null) {
            return new PortalLinkTargets(0, List.of(), 1);
        }
        Long mismatched = portalUserEngineerLinkMapper.countActiveScopeMismatchForEngineer(engineerId, tenantId);
        List<PortalUserEngineerLink> linked =
                portalUserEngineerLinkMapper.selectActiveForEngineerInTenant(engineerId, tenantId);
        List<Long> pending = List.of();
        String email = linkedUser == null || linkedUser.getEmail() == null ? null : linkedUser.getEmail().trim();
        if (email != null && !email.isEmpty() && linkedUser.getTenantId() != null
                && tenantId.equals(linkedUser.getTenantId().trim())) {
            List<Long> candidates = portalUserEngineerLinkMapper.selectUnlinkedPortalUserIdsByEmailInTenant(email, tenantId);
            pending = candidates == null ? List.of() : List.copyOf(candidates);
        }
        return new PortalLinkTargets(linked == null ? 0 : linked.size(), pending,
                mismatched == null ? 0 : mismatched);
    }

    private String portalLinkMessage(PortalLinkTargets targets) {
        if (targets.mismatchedLinks() > 0) {
            return "tenantが一致しないポータルユーザーの紐付けが " + targets.mismatchedLinks()
                    + " 件あります。ポータル管理で紐付けを解除・再登録してから完了してください";
        }
        StringBuilder message = new StringBuilder(targets.linkedCount() > 0
                ? "案件完了確定時に紐付け済みポータルユーザー " + targets.linkedCount() + " 件のセッションを強制失効します"
                : "紐付け済みのポータルユーザーなし");
        if (!targets.pendingPortalUserIds().isEmpty()) {
            message.append("。要確認: emailが一致するが紐付けの無いポータルユーザー ")
                    .append(targets.pendingPortalUserIds().size())
                    .append(" 件は自動処理の対象外です（ポータル管理で紐付けを登録するか、個別に停止してください）");
        }
        return message.toString();
    }

    /** ポータルアカウントの紐付け状況（件数と要確認のポータルユーザーID）。 */
    private record PortalLinkTargets(int linkedCount, List<Long> pendingPortalUserIds, long mismatchedLinks) {
    }

    private LifecycleTask findTaskByCode(Long caseId, String taskCode) {
        if (caseId == null || taskCode == null) return null;
        List<LifecycleTask> tasks = lifecycleTaskMapper.selectByCaseId(caseId);
        if (tasks == null) return null;
        return tasks.stream()
                .filter(t -> taskCode.equals(t.getTaskCode()))
                .findFirst()
                .orElse(null);
    }
}
