package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

/** 組織階層・期間・所属履歴を扱うサービス。 */
public interface OrganizationService extends IService<OrganizationUnit> {

    List<OrganizationUnit> listTree(Long legalEntityId, LocalDate asOf);

    List<Long> descendantIds(Long organizationId, LocalDate asOf);

    boolean deactivate(Long organizationId);

    boolean isReferenced(Long organizationId);

    UserOrganization assignUser(UserOrganization assignment);

    boolean updateUserOrganization(UserOrganization assignment);

    /** 構造変更は旧期間を閉じ、新しい組織行を作成して履歴を保持する。 */
    OrganizationUnit reorganize(Long organizationId, OrganizationUnit replacement, Integer expectedVersion);

    /** 旧組織を統合済みとして記録し、参照先を保持する。 */
    boolean merge(Long organizationId, Long targetOrganizationId, Integer expectedVersion);

    /** 所属の異動は旧行を閉じて新行を追加する。 */
    UserOrganization transferUser(UserOrganization assignment, Integer expectedVersion);

    List<UserOrganization> listUserOrganizations(Long userId, LocalDate asOf);

    @Override
    boolean removeById(Serializable id);
}
