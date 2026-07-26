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

    List<UserOrganization> listUserOrganizations(Long userId, LocalDate asOf);

    @Override
    boolean removeById(Serializable id);
}
