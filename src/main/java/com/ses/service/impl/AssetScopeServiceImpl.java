package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.AssetAssignment;
import com.ses.entity.DocumentLink;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.LicenseAssignmentMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.AssetScopeService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 資産・アカウント・ライセンスの認可母集団を一箇所で解決する。
 *
 * <p>管理者/HR以外は、許可IDをMapperのSQL条件へ渡す。空集合は全件を意味せず、呼び出し側が
 * {@code id = -1} を付けて0件にする。営業・要員は現任担当/本人への現在貸与のみ、マネージャーだけが
 * その母集団へ所有法人条件を積集合する。DocumentLinkも実在文書とassignmentを辿って同じ規則を使う。
 */
@Service
@RequiredArgsConstructor
public class AssetScopeServiceImpl implements AssetScopeService {

    private static final String ROLE_ADMIN = "管理者";
    private static final String ROLE_HR = "HR";
    private static final String ROLE_MANAGER = "マネージャー";
    private static final String ROLE_SALES = "営業";
    private static final String ROLE_ENGINEER = "要員";

    private final SysUserMapper sysUserMapper;
    private final AssetMapper assetMapper;
    private final AssetAssignmentMapper assetAssignmentMapper;
    private final DocumentMapper documentMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final LicenseAssignmentMapper licenseAssignmentMapper;
    private final OrganizationUnitMapper organizationUnitMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final EngineerAccountLinkService engineerAccountLinkService;
    private final OrganizationService organizationService;

    @Override
    public boolean hasFullAccess() {
        return isFullAccess(SecurityUtils.currentRole());
    }

    @Override
    public List<Long> getAccessibleEngineerIds() {
        SysUser current = currentUser(null);
        return current == null ? List.of() : getAccessibleEngineerIds(current.getRole(), current.getId());
    }

    @Override
    public List<Long> getAccessibleEngineerIds(String role, Long actorUserId) {
        if (isFullAccess(role)) {
            return null;
        }
        if (actorUserId == null) {
            SysUser authenticatedUser = currentUser(null);
            actorUserId = authenticatedUser == null ? null : authenticatedUser.getId();
        }
        if (actorUserId == null || role == null) {
            return List.of();
        }
        if (ROLE_ENGINEER.equals(role)) {
            Long engineerId = engineerAccountLinkService.findEngineerIdByUserId(actorUserId);
            return engineerId == null ? List.of() : List.of(engineerId);
        }
        if (ROLE_SALES.equals(role)) {
            return safeList(engineerSalesMapper.selectActiveEngineerIdsBySalesUserId(actorUserId));
        }
        if (ROLE_MANAGER.equals(role)) {
            Set<Long> organizationIds = managerOrganizationIds(actorUserId);
            Set<Long> directUserIds = managerUserIds(actorUserId, organizationIds);
            if (organizationIds.isEmpty() && directUserIds.isEmpty()) {
                return List.of();
            }
            return safeList(engineerAccountLinkMapper.selectEngineerIdsByOrganizationScope(
                    new ArrayList<>(organizationIds), new ArrayList<>(directUserIds), LocalDate.now()));
        }
        return List.of();
    }

    @Override
    public List<Long> getAccessibleAssetIds(String role, Long actorUserId) {
        if (isFullAccess(role)) {
            return null;
        }
        actorUserId = resolveActorUserId(actorUserId);
        List<Long> engineerIds = getAccessibleEngineerIds(role, actorUserId);
        if (engineerIds == null || engineerIds.isEmpty()) {
            return List.of();
        }
        List<Long> ownerCompanyIds = ROLE_MANAGER.equals(role)
                ? new ArrayList<>(accessibleLegalEntityIds(role, actorUserId))
                : null;
        return safeList(assetMapper.selectAccessibleAssetIds(engineerIds, ownerCompanyIds));
    }

    @Override
    public List<Long> getAccessibleLicensePlanIds(String role, Long actorUserId) {
        if (isFullAccess(role)) {
            return null;
        }
        List<Long> engineerIds = getAccessibleEngineerIds(role, resolveActorUserId(actorUserId));
        if (engineerIds == null || engineerIds.isEmpty()) {
            return List.of();
        }
        return safeList(licenseAssignmentMapper.selectActivePlanIdsByEngineerIds(engineerIds));
    }

    @Override
    public boolean isAccessibleAssignee(String assigneeType, Long assigneeId, String role, Long actorUserId) {
        if (assigneeId == null) {
            return false;
        }
        if (isFullAccess(role)) {
            return true;
        }
        actorUserId = resolveActorUserId(actorUserId);
        if (ROLE_ENGINEER.equals(role)) {
            if (!"ENGINEER".equals(assigneeType)) {
                return false;
            }
            Long ownEngineerId = engineerAccountLinkService.findEngineerIdByUserId(actorUserId);
            return ownEngineerId != null && ownEngineerId.equals(assigneeId);
        }
        if ("ENGINEER".equals(assigneeType)) {
            List<Long> allowed = getAccessibleEngineerIds(role, actorUserId);
            return allowed != null && allowed.contains(assigneeId);
        }
        return "USER".equals(assigneeType) && ROLE_MANAGER.equals(role)
                && managerUserIds(actorUserId, managerOrganizationIds(actorUserId)).contains(assigneeId);
    }

    @Override
    public List<Long> getAccessibleAssetDocumentIds(String role, Long actorUserId) {
        if (isFullAccess(role)) {
            return null;
        }
        if (ROLE_ENGINEER.equals(role)) {
            List<Long> engineerIds = getAccessibleEngineerIds(role, actorUserId);
            return engineerIds.isEmpty() ? List.of()
                    : safeList(documentLinkMapper.selectDocumentIdsByEngineerIds(engineerIds));
        }
        List<Long> assetIds = getAccessibleAssetIds(role, actorUserId);
        return assetIds.isEmpty() ? List.of()
                : safeList(documentLinkMapper.selectDocumentIdsByAssetIds(assetIds));
    }

    @Override
    public void assertAccessibleEngineer(Long engineerId) {
        if (engineerId == null || isFullAccess(SecurityUtils.currentRole())) {
            return;
        }
        List<Long> allowed = getAccessibleEngineerIds(SecurityUtils.currentRole(), currentUserId());
        if (allowed == null || !allowed.contains(engineerId)) {
            throw new BusinessException(403, "指定された要員の資産データへのアクセス権限がありません。");
        }
    }

    @Override
    public void assertAccessibleUser(Long userId) {
        if (userId == null || isFullAccess(SecurityUtils.currentRole())) {
            return;
        }
        if (!isAccessibleAssignee("USER", userId, SecurityUtils.currentRole(), currentUserId())) {
            throw new BusinessException(403, "指定されたユーザーへのアクセス権限がありません。");
        }
    }

    @Override
    public boolean isAccessible(Long assetId, String role, Long actorUserId) {
        if (assetId == null) {
            return false;
        }
        if (isFullAccess(role)) {
            return assetMapper.selectById(assetId) != null;
        }
        return getAccessibleAssetIds(role, actorUserId).contains(assetId);
    }

    /** 実在Documentを起点にassignmentとassetの認可を導出する。 */
    @Override
    public boolean isAccessibleByDocumentLink(Long documentId, String role, Long actorUserId) {
        if (documentId == null || documentMapper.selectById(documentId) == null) {
            return false;
        }
        if (isFullAccess(role)) {
            return true;
        }
        for (DocumentLink link : documentLinkMapper.findByDocumentId(documentId)) {
            if (!"ASSET_ASSIGNMENT".equals(link.getTargetType()) || link.getTargetId() == null) {
                continue;
            }
            AssetAssignment assignment = assetAssignmentMapper.selectById(link.getTargetId());
            if (assignment == null) {
                continue;
            }
            // assignmentの記録上の本人は返却後も自己の受領証跡を参照できるが、別要員へ継承しない。
            if (ROLE_ENGINEER.equals(role) && "ENGINEER".equals(assignment.getAssigneeType())
                    && isAccessibleAssignee("ENGINEER", assignment.getAssigneeId(), role, actorUserId)) {
                return true;
            }
            if (isAccessible(assignment.getAssetId(), role, actorUserId)) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> managerOrganizationIds(Long managerUserId) {
        Set<Long> result = new HashSet<>();
        for (UserOrganization assignment : activeUserOrganizations(managerUserId)) {
            if (Integer.valueOf(1).equals(assignment.getPrimaryFlag()) && assignment.getOrganizationId() != null) {
                result.addAll(organizationService.descendantIds(assignment.getOrganizationId(), LocalDate.now()));
            }
        }
        return result;
    }

    private Set<Long> managerUserIds(Long managerUserId, Set<Long> organizationIds) {
        Set<Long> result = userOrganizationMapper.selectActiveByManagerUserId(managerUserId, LocalDate.now())
                .stream().map(UserOrganization::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!organizationIds.isEmpty()) {
            result.addAll(userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                    .select(UserOrganization::getUserId)
                    .in(UserOrganization::getOrganizationId, organizationIds)
                    .le(UserOrganization::getValidFrom, LocalDate.now())
                    .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, LocalDate.now())))
                    .stream().map(UserOrganization::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        }
        return result;
    }

    private Set<Long> accessibleLegalEntityIds(String role, Long actorUserId) {
        if (!ROLE_SALES.equals(role) && !ROLE_MANAGER.equals(role)) {
            return Set.of();
        }
        actorUserId = resolveActorUserId(actorUserId);
        Set<Long> organizationIds = ROLE_MANAGER.equals(role)
                ? managerOrganizationIds(actorUserId)
                : activeUserOrganizations(actorUserId).stream()
                .filter(a -> Integer.valueOf(1).equals(a.getPrimaryFlag()))
                .map(UserOrganization::getOrganizationId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> result = new HashSet<>();
        for (Long organizationId : organizationIds) {
            OrganizationUnit organization = organizationUnitMapper.selectAt(organizationId, LocalDate.now());
            if (organization != null && organization.getLegalEntityId() != null) {
                result.add(organization.getLegalEntityId());
            }
        }
        return result;
    }

    private List<UserOrganization> activeUserOrganizations(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return userOrganizationMapper.selectList(new LambdaQueryWrapper<UserOrganization>()
                .eq(UserOrganization::getUserId, userId)
                .le(UserOrganization::getValidFrom, LocalDate.now())
                .and(w -> w.isNull(UserOrganization::getValidTo).or().ge(UserOrganization::getValidTo, LocalDate.now())));
    }

    private SysUser currentUser(Long explicitUserId) {
        if (explicitUserId != null) {
            return sysUserMapper.selectById(explicitUserId);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        return sysUserMapper.selectByUsername(auth.getName());
    }

    private Long currentUserId() {
        Long userId = SecurityUtils.currentUserId();
        if (userId != null) {
            return userId;
        }
        SysUser user = currentUser(null);
        return user == null ? null : user.getId();
    }

    private Long resolveActorUserId(Long actorUserId) {
        if (actorUserId != null) {
            return actorUserId;
        }
        SysUser user = currentUser(null);
        return user == null ? null : user.getId();
    }

    private boolean isFullAccess(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_HR.equals(role);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
