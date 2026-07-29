package com.ses.service.security;

import com.ses.entity.PermissionGroup;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Set;

/** 権限グループ参照とユーザー割当を管理する。 */
public interface PermissionGroupManagementService {

    List<PermissionGroup> listGroups();

    Set<Long> assignedGroupIds(Long userId);

    void replaceAssignments(Long userId, Set<Long> groupIds, Authentication authentication);
}

