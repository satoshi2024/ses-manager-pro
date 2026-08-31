package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.SysUser;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ライフサイクル案件・タスクの認可スコープ判定サービス
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleScopeService {

    private final OrganizationScopeService organizationScopeService;
    private final EngineerSalesService engineerSalesService;
    private final EngineerAccountLinkService engineerAccountLinkService;

    /**
     * 要員起票・参照権限チェック
     */
    public void assertCanAccessEngineer(SysUser currentUser, Engineer engineer) {
        if (currentUser == null) {
            throw BusinessException.of(401, "error.unauthorized");
        }
        String role = currentUser.getRole();
        if ("管理者".equals(role) || "HR".equals(role)) {
            return;
        }
        if ("マネージャー".equals(role)) {
            if (engineer.getOrganizationId() != null) {
                Set<Long> allowedOrgs = organizationScopeService.allowedOrganizationIds(LocalDate.now());
                if (organizationScopeService.hasFullAccess() || allowedOrgs.contains(engineer.getOrganizationId())) {
                    return;
                }
            }
            throw BusinessException.of(403, "error.lifecycle.accessDenied", "管轄外の要員に対する案件起票はできません");
        }
        if ("営業".equals(role)) {
            List<EngineerSalesDto> salesList = engineerSalesService.listActive(engineer.getId());
            boolean isAssigned = salesList.stream().anyMatch(s -> Objects.equals(s.getSalesUserId(), currentUser.getId()));
            if (isAssigned) {
                return;
            }
            throw BusinessException.of(403, "error.lifecycle.accessDenied", "担当外の要員に対する案件起票はできません");
        }
    }

    /**
     * 案件の閲覧権限チェック
     */
    public void assertCanViewCase(SysUser currentUser, LifecycleCase lcCase, Engineer engineer) {
        if (!canViewCase(currentUser, lcCase, engineer)) {
            throw BusinessException.of(403, "error.lifecycle.accessDenied", "この案件の閲覧権限がありません");
        }
    }

    /**
     * 案件の更新・進行・完了権限チェック
     */
    public void assertCanEditCase(SysUser currentUser, LifecycleCase lcCase, Engineer engineer) {
        if (currentUser == null) {
            throw BusinessException.of(401, "error.unauthorized");
        }
        String role = currentUser.getRole();
        // 管理者・HRは全案件を編集可能
        if ("管理者".equals(role) || "HR".equals(role)) {
            return;
        }

        // 要員本人は案件ステータスを直接変更できない
        if ("要員".equals(role)) {
            throw BusinessException.of(403, "error.lifecycle.engineerCannotEditCase", "要員ロールは案件ステータスを変更できません");
        }

        // マネージャーは自組織配下の要員案件のみ編集可能
        if ("マネージャー".equals(role)) {
            if (engineer.getOrganizationId() != null) {
                Set<Long> allowedOrgs = organizationScopeService.allowedOrganizationIds(LocalDate.now());
                if (organizationScopeService.hasFullAccess() || allowedOrgs.contains(engineer.getOrganizationId())) {
                    return;
                }
            }
            throw BusinessException.of(403, "error.lifecycle.accessDenied", "管轄外の要員案件は編集できません");
        }

        // 営業は自身が起票したか担当している要員案件のみ
        if ("営業".equals(role)) {
            if (Objects.equals(lcCase.getApplicantUserId(), currentUser.getId())) {
                return;
            }
            List<EngineerSalesDto> salesList = engineerSalesService.listActive(engineer.getId());
            boolean isAssigned = salesList.stream().anyMatch(s -> Objects.equals(s.getSalesUserId(), currentUser.getId()));
            if (isAssigned) {
                return;
            }
            throw BusinessException.of(403, "error.lifecycle.accessDenied", "担当外の要員案件は編集できません");
        }

        throw BusinessException.of(403, "error.lifecycle.accessDenied", "案件の編集権限がありません");
    }

    /**
     * 案件閲覧可否判定
     */
    public boolean canViewCase(SysUser currentUser, LifecycleCase lcCase, Engineer engineer) {
        if (currentUser == null) return false;
        String role = currentUser.getRole();

        if ("管理者".equals(role) || "HR".equals(role)) {
            return true;
        }

        if ("要員".equals(role)) {
            EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineer.getId());
            return link != null && Objects.equals(link.getSysUserId(), currentUser.getId());
        }

        if ("マネージャー".equals(role)) {
            if (organizationScopeService.hasFullAccess()) return true;
            if (engineer.getOrganizationId() != null) {
                Set<Long> allowedOrgs = organizationScopeService.allowedOrganizationIds(LocalDate.now());
                return allowedOrgs.contains(engineer.getOrganizationId());
            }
            return false;
        }

        if ("営業".equals(role)) {
            if (Objects.equals(lcCase.getApplicantUserId(), currentUser.getId())) {
                return true;
            }
            List<EngineerSalesDto> salesList = engineerSalesService.listActive(engineer.getId());
            return salesList.stream().anyMatch(s -> Objects.equals(s.getSalesUserId(), currentUser.getId()));
        }

        return false;
    }

    /**
     * タスクの閲覧可否判定（本人非公開タスクのフィルタリング含む）
     * <p>
     * 表2（design.md §6）の可見母集団ルール:
     * <ul>
     *   <li>管理者/HR: 全タスク可視</li>
     *   <li>マネージャー: 内部タスク含む全タスク可視</li>
     *   <li>営業: {@code is_engineer_visible=1} のタスク、および内部タスク（{@code is_engineer_visible=0}）のうち
     *       営業関係（担当ロールが「営業」または担当ユーザーが自分自身）のタスクのみ可視。HR機密タスクはマスク。</li>
     *   <li>要員: {@code is_engineer_visible=1} のタスクのみ</li>
     * </ul>
     */
    public boolean isTaskVisibleToUser(SysUser currentUser, LifecycleTask task) {
        if (currentUser == null) return false;
        String role = currentUser.getRole();

        if ("要員".equals(role)) {
            return task.getIsEngineerVisible() != null && task.getIsEngineerVisible() == 1;
        }

        // 営業: 本人公開タスクは常に可視。内部タスク（is_engineer_visible=0）は
        // 営業関係（assigneeRole="営業" または 担当ユーザーが自分自身）のみ可視。HR機密タスクはマスク。
        if ("営業".equals(role)) {
            if (task.getIsEngineerVisible() != null && task.getIsEngineerVisible() == 1) {
                return true;
            }
            // 内部タスクのうち営業関係のみ可視
            return "営業".equals(task.getAssigneeRole())
                    || (task.getAssigneeUserId() != null && Objects.equals(task.getAssigneeUserId(), currentUser.getId()));
        }

        // 管理者・HR・マネージャーは全タスク可視
        return true;
    }

    /**
     * タスクの完了・更新・訂正権限チェック
     */
    public void assertCanEditTask(SysUser currentUser, LifecycleTask task, LifecycleCase lcCase, Engineer engineer) {
        if (currentUser == null) {
            throw BusinessException.of(401, "error.unauthorized");
        }

        String role = currentUser.getRole();

        // 管理者・HRは全タスクを更新可能
        if ("管理者".equals(role) || "HR".equals(role)) {
            return;
        }

        // 担当者本人であれば実行可能（本人がアサインされているタスク）
        if (task.getAssigneeUserId() != null && Objects.equals(task.getAssigneeUserId(), currentUser.getId())) {
            // 要員本人の場合は内部非公開タスクでないことを確認
            if ("要員".equals(role) && (task.getIsEngineerVisible() == null || task.getIsEngineerVisible() != 1)) {
                throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
            }
            return;
        }

        // 閲覧不可のタスク（スコープ外または非公開HRタスク）への操作は存在を推測させない 404 で拒否
        if (!canViewCase(currentUser, lcCase, engineer) || !isTaskVisibleToUser(currentUser, task)) {
            throw BusinessException.of(404, "error.lifecycle.taskNotFound", "タスクが見つかりません");
        }

        // 要員本人の場合: 本人公開タスクかつ自身が担当者であること (上記本人チェックを通過しなかった場合は拒否)
        if ("要員".equals(role)) {
            throw BusinessException.of(403, "error.lifecycle.notAssignedToYou", "あなたに割り当てられたタスクではありません");
        }

        // ロール担当タスクであれば、そのロール保持者なら実行可能
        if (task.getAssigneeRole() != null && task.getAssigneeRole().equals(role)) {
            return;
        }

        // マネージャーで管轄内の場合
        if ("マネージャー".equals(role)) {
            if (canViewCase(currentUser, lcCase, engineer)) {
                return;
            }
        }

        // 営業で管轄内かつ営業関係タスクの場合
        if ("営業".equals(role)) {
            if (canViewCase(currentUser, lcCase, engineer) && isTaskVisibleToUser(currentUser, task)) {
                return;
            }
        }

        throw BusinessException.of(403, "error.lifecycle.taskEditDenied", "このタスクを実行する権限がありません");
    }
}
