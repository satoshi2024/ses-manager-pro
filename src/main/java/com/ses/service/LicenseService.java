package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.LicenseAssignment;
import com.ses.entity.LicensePlan;

import java.time.LocalDate;
import java.util.List;

/**
 * 有償ライセンス・席数管理サービス
 */
public interface LicenseService extends IService<LicensePlan> {

    /**
     * ライセンスプランを新規作成または更新する
     */
    LicensePlan savePlan(LicensePlan plan, Long actorUserId);

    /**
     * ライセンスを割り当てる（席数上限CAS保護）
     */
    LicenseAssignment assignLicense(Long planId,
                                   String assigneeType,
                                   Long assigneeId,
                                   Long accountReferenceId,
                                   LocalDate assignedDate,
                                   Long actorUserId);

    /**
     * ライセンス割当を解除する（席数減算CAS保護）
     */
    LicenseAssignment releaseLicense(Long assignmentId,
                                     LocalDate releasedDate,
                                     Long actorUserId);

    /**
     * 要員またはユーザーの有効ライセンス割当一覧を取得する
     */
    List<LicenseAssignment> getActiveAssignmentsByAssignee(String assigneeType, Long assigneeId);

    /**
     * プランごとの割当一覧を取得する
     */
    List<LicenseAssignment> getAssignmentsByPlanId(Long planId);

    /**
     * プラン一覧検索（ページネーション）
     */
    IPage<LicensePlan> searchPlans(int page, int size, String keyword, String status);
}
