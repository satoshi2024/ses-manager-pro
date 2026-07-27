package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 組織階層・期間・所属履歴を扱うサービス。 */
public interface OrganizationService extends IService<OrganizationUnit> {

    List<OrganizationUnit> listTree(Long legalEntityId, LocalDate asOf);

    List<Long> descendantIds(Long organizationId, LocalDate asOf);

    boolean deactivate(Long organizationId);

    /** 状態変更を要求版番号付きCASで行う。無効化時は子組織・在籍者を検査する。 */
    boolean updateStatus(Long organizationId, String status, Integer expectedVersion);

    boolean isReferenced(Long organizationId);

    UserOrganization assignUser(UserOrganization assignment);

    boolean updateUserOrganization(UserOrganization assignment, Integer expectedVersion);

    /**
     * 組織属性の更新。<b>同じ組織IDのまま</b>版番号CASで更新する。
     *
     * <p>編集のたびに新しい組織行を作ると、所属・原価部門・予算・月次snapshotが旧IDを指したまま
     * 取り残される。R1.3が保持を求める「異動履歴」は {@code t_user_organization} と
     * {@code t_monthly_accounting_dimension} が別行として持っており、組織マスタ行を分岐させる必要はない。
     */
    boolean updateOrganization(OrganizationUnit entity, Integer expectedVersion);

    /**
     * 組織の統合。旧組織を無効化して {@code merged_into} を記録し、
     * <b>生きている参照（子組織・有効な所属・原価部門・当月以降の予算）を統合先へ付け替える</b>。
     * 過去月の予算と月次snapshotは実績の事実なので動かさない（R2.2）。
     */
    boolean merge(Long organizationId, Long targetOrganizationId, Integer expectedVersion);

    /** 所属の異動は旧行を閉じて新行を追加する。{@code expectedVersion} は閉じる対象の版番号として検査する。 */
    UserOrganization transferUser(UserOrganization assignment, Integer expectedVersion);

    /** 所属の解除（終了日を入れて閉じる）。論理削除ではなく履歴として残す。 */
    boolean releaseAssignment(Long assignmentId, LocalDate releaseDate, Integer expectedVersion);

    /**
     * 退職・アカウント停止時に有効な所属をすべて閉じる。
     * 閉じないと退職者が組織scopeの母集団と部門損益の帰属に残り続ける。
     *
     * @return 閉じた件数
     */
    int closeAssignmentsForUser(Long userId, LocalDate releaseDate);

    List<UserOrganization> listUserOrganizations(Long userId, LocalDate asOf);

    /**
     * 組織IDから組織名を引く。<b>状態・有効期間で絞らない</b>。
     * 無効化・統合済みの組織にも過去実績がぶら下がっており、名前を引けないと
     * 「全社合計と組織別合計の総和が一致する」(R4)の突合ができなくなるため。
     */
    Map<Long, String> namesByIds(java.util.Collection<Long> organizationIds);

    @Override
    boolean removeById(Serializable id);
}
