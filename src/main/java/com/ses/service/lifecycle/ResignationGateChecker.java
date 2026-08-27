package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.dto.lifecycle.ResignationGateResultDto.GateItemResult;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.OrganizationService;
import com.ses.service.portal.PortalSessionService;
import com.ses.service.security.PersistentSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
    private final OrganizationService organizationService;
    private final PersistentSessionService persistentSessionService;
    private final PortalSessionService portalSessionService;
    private final ExpenseRequestMapper expenseRequestMapper;
    private final LifecycleTaskMapper lifecycleTaskMapper;

    /**
     * 退社ゲートの全項目を検証する。
     */
    public ResignationGateResultDto evaluate(LifecycleCase lcCase, Engineer engineer) {
        List<GateItemResult> items = new ArrayList<>();
        boolean allPassed = true;

        Long engineerId = engineer.getId();
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        Long linkedUserId = link != null ? link.getSysUserId() : null;

        // 1. 内部アカウント無効化 (USER_DEACTIVATION)
        boolean userDeactivated = true;
        String userMsg = "アカウント停止確認済み";
        if (linkedUserId != null) {
            SysUser user = sysUserMapper.selectById(linkedUserId);
            if (user != null && user.getStatus() != null && user.getStatus() != 0) {
                userDeactivated = false;
                userMsg = "ログインアカウント(ID: " + user.getUsername() + ")が有効なままです。ステータスを無効(0)に変更してください。";
            }
        }
        if (!userDeactivated) allPassed = false;
        items.add(GateItemResult.builder()
                .code("USER_DEACTIVATION")
                .name("ログインアカウント無効化")
                .passed(userDeactivated)
                .autoExecutable(false)
                .message(userMsg)
                .build());

        // 2. Webセッション失効 (SESSION_REVOCATION - 案件完了時に自動実行可能)
        items.add(GateItemResult.builder()
                .code("SESSION_REVOCATION")
                .name("全セッション強制失効")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時に内部およびポータルの有効セッションを強制失効します")
                .build());

        // 3. 要員ポータル連携解除または無効化 (PORTAL_UNLINK)
        boolean portalUnlinked = true;
        String portalMsg = "ポータル連携確認済み";
        if (link != null && !userDeactivated) {
            portalUnlinked = false;
            portalMsg = "要員アカウント連携が存在し、ユーザーアカウントが有効です";
        }
        if (!portalUnlinked) allPassed = false;
        items.add(GateItemResult.builder()
                .code("PORTAL_UNLINK")
                .name("要員ポータル連携解除・無効化")
                .passed(portalUnlinked)
                .autoExecutable(false)
                .message(portalMsg)
                .build());

        // 4. 担当営業割当の解除・引継ぎ (SALES_RELEASE)
        List<EngineerSalesDto> sales = engineerSalesService.listActive(engineerId);
        boolean salesReleased = true;
        String salesMsg = "担当営業引継ぎ・解除完了";
        if (sales != null && !sales.isEmpty()) {
            boolean hasPrimary = sales.stream().anyMatch(s -> s.getPrimaryFlag() != null && s.getPrimaryFlag() == 1);
            if (hasPrimary) {
                salesReleased = false;
                salesMsg = "有効な主担当営業が設定されたままです。割当解除または引継ぎを行ってください。";
            }
        }
        // 阻害タスクに例外承認があるか確認
        LifecycleTask salesTask = findTaskByCode(lcCase.getId(), "RESIGN_SALES_RELEASE");
        boolean salesWaived = salesTask != null && "WAIVED".equals(salesTask.getStatus());
        if (!salesReleased && !salesWaived) allPassed = false;
        items.add(GateItemResult.builder()
                .code("SALES_RELEASE")
                .name("担当営業の引継ぎ・割当解除")
                .passed(salesReleased || salesWaived)
                .autoExecutable(false)
                .waived(salesWaived)
                .approvalRequestId(salesTask != null ? salesTask.getApprovalRequestId() : null)
                .message(salesWaived ? "例外承認により免除済み" : salesMsg)
                .build());

        // 5. 組織所属の終了 (ORG_ASSIGNMENT_CLOSE - 自動実行可能)
        items.add(GateItemResult.builder()
                .code("ORG_ASSIGNMENT_CLOSE")
                .name("組織所属の終了")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時に有効な組織所属を自動的に閉鎖します")
                .build());

        // 6. 貸与資産の返却 (ASSET_RETURN)
        LifecycleTask assetTask = findTaskByCode(lcCase.getId(), "RESIGN_ASSET_RETURN");
        boolean assetReturned = true;
        boolean assetWaived = false;
        String assetMsg = "貸与物返却確認済み";
        if (assetTask != null) {
            if ("COMPLETED".equals(assetTask.getStatus())) {
                assetReturned = true;
            } else if ("WAIVED".equals(assetTask.getStatus())) {
                assetReturned = true;
                assetWaived = true;
                assetMsg = "例外承認により返却免除済み";
            } else {
                assetReturned = false;
                assetMsg = "貸与資産返却タスク（PC、スマートフォン、入館証等）が未完了です";
            }
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

        return ResignationGateResultDto.builder()
                .passed(allPassed)
                .summary(allPassed ? "退社ゲート全項目PASS。案件を完了可能です。" : "退社ゲートに未充足の項目が存在します。")
                .items(items)
                .build();
    }

    /**
     * 案件完了確定時に退社ゲートの自動実行処理（セッション強制失効・組織閉鎖）を行う。
     */
    public void executeAutomaticGateActions(LifecycleCase lcCase, Engineer engineer) {
        Long engineerId = engineer.getId();
        LocalDate anchorDate = lcCase.getAnchorDate() != null ? lcCase.getAnchorDate() : LocalDate.now();

        // 1. 組織所属の閉鎖
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        if (link != null && link.getSysUserId() != null) {
            int closedCount = organizationService.closeAssignmentsForUser(link.getSysUserId(), anchorDate);
            log.info("Closed {} organization assignments for user {}", closedCount, link.getSysUserId());

            // 2. Webセッションおよびポータルセッション強制失効
            try {
                persistentSessionService.revokeAllForUser(link.getSysUserId(), "退社案件完了によるセッション強制失効");
                portalSessionService.revokeAllForUser(link.getSysUserId(), "退社案件完了によるセッション強制失効");
                log.info("Revoked all sessions for user {}", link.getSysUserId());
            } catch (Exception e) {
                log.warn("Failed to revoke session for user {}: {}", link.getSysUserId(), e.getMessage());
            }
        }
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
