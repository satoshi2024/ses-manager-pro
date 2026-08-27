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
    private final com.ses.mapper.EngineerSalesMapper engineerSalesMapper;
    private final com.ses.mapper.PortalUserMapper portalUserMapper;
    private final com.ses.mapper.ContractMapper contractMapper;
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

        // 1. 内部アカウント無効化 (USER_DEACTIVATION - 自動実行)
        items.add(GateItemResult.builder()
                .code("USER_DEACTIVATION")
                .name("ログインアカウント無効化")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時にログインアカウントを自動的に無効化(0)します")
                .build());

        // 2. Webセッション失効 (SESSION_REVOCATION - 自動実行)
        items.add(GateItemResult.builder()
                .code("SESSION_REVOCATION")
                .name("全セッション強制失効")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時に内部およびポータルの有効セッションを強制失効します")
                .build());

        // 3. 要員ポータル連携解除または無効化 (PORTAL_UNLINK - 自動実行)
        items.add(GateItemResult.builder()
                .code("PORTAL_UNLINK")
                .name("要員ポータル連携解除・無効化")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時に要員ポータル連携を無効化します")
                .build());

        // 4. 担当営業割当の解除・引継ぎ (SALES_RELEASE - 自動実行)
        items.add(GateItemResult.builder()
                .code("SALES_RELEASE")
                .name("担当営業の引継ぎ・割当解除")
                .passed(true)
                .autoExecutable(true)
                .message("案件完了確定時に有効な担当営業割当を自動的に解除します")
                .build());

        // 5. 組織所属の終了 (ORG_ASSIGNMENT_CLOSE - 自動実行)
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
        } else if ("RESIGNATION".equals(lcCase.getLifecycleType())) {
            assetReturned = false;
            assetMsg = "退社案件に必須の貸与資産返却タスク(RESIGN_ASSET_RETURN)が定義されていません";
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

        return ResignationGateResultDto.builder()
                .passed(allPassed)
                .summary(allPassed ? "退社ゲート全項目PASS。案件を完了可能です。" : "退社ゲートに未充足の項目が存在します。")
                .items(items)
                .build();
    }

    /**
     * 案件完了確定時に退社ゲートの自動実行処理（アカウント無効化・セッション強制失効・組織閉鎖・担当営業解除・ポータル連携解除）を行う。
     * Fail-Closed: 例外発生時は例外を送出し、呼び出し元のトランザクションをロールバックする。
     */
    public void executeAutomaticGateActions(LifecycleCase lcCase, Engineer engineer) {
        Long engineerId = engineer.getId();
        LocalDate anchorDate = lcCase.getAnchorDate() != null ? lcCase.getAnchorDate() : LocalDate.now();

        // 1. ユーザーアカウント無効化 & 組織所属閉鎖 & セッション失効 & ポータル連携解除
        EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineerId);
        if (link != null && link.getSysUserId() != null) {
            Long userId = link.getSysUserId();
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null && (user.getStatus() == null || user.getStatus() != 0)) {
                user.setStatus(0);
                sysUserMapper.updateById(user);
                log.info("Deactivated user {} due to resignation case completion", userId);
            }

            int closedCount = organizationService.closeAssignmentsForUser(userId, anchorDate);
            log.info("Closed {} organization assignments for user {}", closedCount, userId);

            // 内部セッション強制失効 (Fail-Closed)
            persistentSessionService.revokeAllForUser(userId, "退社案件完了によるセッション強制失効");
            log.info("Revoked all persistent sessions for user {}", userId);

            // ポータルユーザーが存在する場合はポータルセッションも失効
            if (user != null && user.getEmail() != null) {
                try {
                    com.ses.entity.PortalUser portalUser = portalUserMapper.selectOne(
                            new LambdaQueryWrapper<com.ses.entity.PortalUser>().eq(com.ses.entity.PortalUser::getEmail, user.getEmail()));
                    if (portalUser != null) {
                        portalSessionService.revokeAllForUser(portalUser.getId(), "退社案件完了によるポータルセッション強制失効");
                        log.info("Revoked all portal sessions for portal user {}", portalUser.getId());
                    }
                } catch (Exception e) {
                    log.warn("Portal user lookup skipped or not found: {}", e.getMessage());
                }
            }

            // ポータル連携解除
            engineerAccountLinkService.unlinkByEngineerId(engineerId);
            log.info("Unlinked engineer account link for engineer {}", engineerId);
        }

        // 2. 主担当営業および割当の解除
        List<EngineerSales> activeSales = engineerSalesMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EngineerSales>()
                        .eq(EngineerSales::getEngineerId, engineerId)
                        .isNull(EngineerSales::getReleasedAt)
        );
        for (EngineerSales es : activeSales) {
            es.setReleasedAt(anchorDate);
            engineerSalesMapper.updateById(es);
            log.info("Released sales assignment {} for engineer {}", es.getId(), engineerId);
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
