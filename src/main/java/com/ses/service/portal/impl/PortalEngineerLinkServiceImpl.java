package com.ses.service.portal.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.portal.PortalUserEngineerLinkDto;
import com.ses.entity.AuditLog;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.entity.PortalUserEngineerLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserEngineerLinkMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import com.ses.service.portal.PortalEngineerLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * ポータルユーザーと要員の紐付けの登録・解除（NF01 N1-4 / 決定 Q-10・B-NF01-8）。
 *
 * <p>lock順（NF01細粒度epoch計画 §3.1）: 認可のための読取（操作者・ポータルユーザー・組織。lockしない）→
 * 順7 ENGINEER: 対象要員の {@code t_engineer} 行を {@code FOR UPDATE} → 順12 PORTAL_ENGINEER_LINK: 紐付け行の追加・解除 →
 * 葉: 監査。要員行を先に排他することで、要員行を保持して紐付けを読む退社完了（W3-16b）と直列化する。
 * 紐付け行を先にlockしてから要員行へ戻ることはしない（解除の対象要員は事前の非lock読取で特定し、判定は
 * 要員lock後のCAS更新の結果で行う）。</p>
 *
 * <p>現行紐付けの一意性はV188の一意制約が正であり、登録前の確認（非lock読取）は分かりやすい409を返すためだけに使う。
 * 並行登録で確認を両方が通過した場合も、一意制約違反を同じ409へ変換する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalEngineerLinkServiceImpl implements PortalEngineerLinkService {

    /** V188の現行紐付け一意index。 */
    static final String ACTIVE_LINK_UNIQUE_KEY = "uk_portal_user_engineer_link_active";

    private static final String AUDIT_REFERENCE_TYPE = "PORTAL_USER_ENGINEER_LINK";
    private static final String ADMIN_ROLE = "管理者";

    private final PortalUserEngineerLinkMapper linkMapper;
    private final PortalUserMapper portalUserMapper;
    private final PortalOrganizationMapper organizationMapper;
    private final EngineerMapper engineerMapper;
    private final LifecycleCaseMapper lifecycleCaseMapper;
    private final SysUserMapper sysUserMapper;
    private final AuditLogService auditLogService;

    @Override
    public List<PortalUserEngineerLinkDto> listForPortalUser(Long portalUserId) {
        SysUser actor = requireAdminActor();
        String tenantId = actor.getTenantId().trim();
        requirePortalUserInTenant(portalUserId, tenantId);
        return linkMapper.selectList(new LambdaQueryWrapper<PortalUserEngineerLink>()
                        .eq(PortalUserEngineerLink::getPortalUserId, portalUserId)
                        .eq(PortalUserEngineerLink::getTenantId, tenantId)
                        .orderByDesc(PortalUserEngineerLink::getId))
                .stream()
                .map(link -> toDto(link, tenantId))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalUserEngineerLinkDto register(Long portalUserId, Long engineerId) {
        SysUser actor = requireAdminActor();
        String tenantId = actor.getTenantId().trim();
        if (engineerId == null) {
            throw BusinessException.of(400, "error.portalAdmin.engineerLink.engineerRequired");
        }
        requirePortalUserInTenant(portalUserId, tenantId);

        // 順7 ENGINEER: 対象要員行を排他してから紐付けを書く（退社完了との直列化）。他tenantの要員は存在しないものと同じ404。
        Engineer engineer = engineerMapper.selectByIdForUpdateForTenant(engineerId, tenantId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.portalAdmin.engineerLink.engineerNotFound");
        }
        if (linkMapper.selectActiveByPortalUserId(portalUserId) != null) {
            throw alreadyLinked();
        }

        LocalDateTime now = LocalDateTime.now();
        PortalUserEngineerLink link = new PortalUserEngineerLink();
        link.setTenantId(tenantId);
        link.setPortalUserId(portalUserId);
        link.setEngineerId(engineer.getId());
        link.setLinkedBy(actor.getId());
        link.setLinkedAt(now);
        try {
            linkMapper.insert(link);
        } catch (DuplicateKeyException ex) {
            if (violatesActiveLinkKey(ex)) {
                throw alreadyLinked();
            }
            throw ex;
        }

        // 退社完了済みの要員へ後から紐付けても、完了時の自動処理は遡って行われない。拒否はせず（再入社等で正当な場合がある）、
        // 「要確認」として画面へ返し監査にも残す（NF01細粒度epoch計画 §5.14 の未決事項への決定）。
        boolean resigned = resignationCompleted(engineer.getId(), tenantId);
        audit(actor, "POST", "/api/portal-admin/users/" + portalUserId + "/engineer-links",
                "PORTAL_ENGINEER_LINK_REGISTERED", link.getId(), "UNLINKED",
                "LINKED;portalUserId=" + portalUserId + ";engineerId=" + engineer.getId()
                        + (resigned ? ";engineerResigned=true" : ""));
        log.info("ポータルユーザーと要員の紐付けを登録しました: linkId={}, portalUserId={}, engineerId={}, engineerResigned={}",
                link.getId(), portalUserId, engineer.getId(), resigned);
        PortalUserEngineerLinkDto dto = toDto(link, engineer);
        dto.setEngineerResigned(resigned);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PortalUserEngineerLinkDto release(Long linkId) {
        SysUser actor = requireAdminActor();
        String tenantId = actor.getTenantId().trim();
        // 対象要員の特定だけの非lock読取。解除できるかどうかは要員lock後のCAS更新で判定する。
        PortalUserEngineerLink located = linkId == null ? null : linkMapper.selectById(linkId);
        if (located == null || !tenantId.equals(located.getTenantId() == null ? null : located.getTenantId().trim())) {
            throw BusinessException.of(404, "error.portalAdmin.engineerLink.notFound");
        }

        // 順7 ENGINEER → 順12 PORTAL_ENGINEER_LINK。要員がtenantに無い（不整合）場合も解除は許す。
        engineerMapper.selectByIdForUpdateForTenant(located.getEngineerId(), tenantId);
        LocalDateTime now = LocalDateTime.now();
        int updated = linkMapper.update(null, new UpdateWrapper<PortalUserEngineerLink>()
                .eq("id", linkId)
                .eq("tenant_id", tenantId)
                .isNull("released_at")
                .eq("deleted_flag", 0)
                .set("released_at", now)
                .set("released_by", actor.getId())
                .set("updated_at", now));
        if (updated != 1) {
            throw BusinessException.of(409, "error.portalAdmin.engineerLink.alreadyReleased");
        }

        audit(actor, "POST", "/api/portal-admin/engineer-links/" + linkId + "/release",
                "PORTAL_ENGINEER_LINK_RELEASED", linkId,
                "LINKED;portalUserId=" + located.getPortalUserId() + ";engineerId=" + located.getEngineerId(),
                "RELEASED");
        log.info("ポータルユーザーと要員の紐付けを解除しました: linkId={}, portalUserId={}, engineerId={}",
                linkId, located.getPortalUserId(), located.getEngineerId());
        PortalUserEngineerLink released = linkMapper.selectById(linkId);
        return toDto(released != null ? released : located, tenantId);
    }

    /**
     * 操作者を管理者に限定し、そのtenantを返す。永続化された利用者を正とし、request principalの役割・tenantと
     * 食い違う場合も拒否する（他画面の管理者判定と同じくfail-closed）。
     */
    private SysUser requireAdminActor() {
        Long userId = SecurityUtils.currentUserId();
        SysUser actor = userId == null ? null : sysUserMapper.selectById(userId);
        if (actor == null || !Integer.valueOf(1).equals(actor.getStatus())
                || !ADMIN_ROLE.equals(actor.getRole()) || !ADMIN_ROLE.equals(SecurityUtils.currentRole())
                || actor.getTenantId() == null || actor.getTenantId().isBlank()) {
            throw BusinessException.of(403, "error.forbidden");
        }
        String requestTenant = SecurityUtils.currentTenantId();
        if (requestTenant != null && !requestTenant.isBlank() && !actor.getTenantId().trim().equals(requestTenant.trim())) {
            throw BusinessException.of(403, "error.forbidden");
        }
        return actor;
    }

    /** ポータルユーザーとその組織が操作者のtenantに属することを要求する。他tenant・存在しない場合は同じ404。 */
    private PortalUser requirePortalUserInTenant(Long portalUserId, String tenantId) {
        PortalUser portalUser = portalUserId == null ? null : portalUserMapper.selectById(portalUserId);
        PortalOrganization organization = portalUser == null || portalUser.getPortalOrgId() == null
                ? null : organizationMapper.selectById(portalUser.getPortalOrgId());
        if (organization == null || organization.getTenantId() == null
                || !tenantId.equals(organization.getTenantId().trim())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return portalUser;
    }

    /** 要員に完了済みの退社案件があり、未終結の退社案件が無いか（退社完了後の紐付けを「要確認」として返す）。 */
    private boolean resignationCompleted(Long engineerId, String tenantId) {
        Long completed = lifecycleCaseMapper.selectCount(new LambdaQueryWrapper<LifecycleCase>()
                .eq(LifecycleCase::getTenantId, tenantId)
                .eq(LifecycleCase::getEngineerId, engineerId)
                .eq(LifecycleCase::getLifecycleType, "RESIGNATION")
                .eq(LifecycleCase::getStatus, "COMPLETED"));
        if (completed == null || completed == 0) {
            return false;
        }
        Long open = lifecycleCaseMapper.selectCount(new LambdaQueryWrapper<LifecycleCase>()
                .eq(LifecycleCase::getTenantId, tenantId)
                .eq(LifecycleCase::getEngineerId, engineerId)
                .eq(LifecycleCase::getLifecycleType, "RESIGNATION")
                .notIn(LifecycleCase::getStatus, List.of("COMPLETED", "CANCELLED")));
        return open == null || open == 0;
    }

    /** 登録・解除の監査をドメイン状態変更と同一transactionで必須記録する（記録できなければ操作ごと取り消す）。 */
    private void audit(SysUser actor, String method, String uri, String applicationCode, Long linkId,
                       String beforeState, String afterState) {
        AuditLog entry = new AuditLog();
        entry.setUsername(actor.getUsername());
        entry.setMethod(method);
        entry.setUri(uri);
        entry.setStatus(200);
        entry.setApplicationCode(applicationCode);
        entry.setSuccessFlag(true);
        entry.setReferenceType(AUDIT_REFERENCE_TYPE);
        entry.setReferenceId(linkId);
        entry.setActorType(ActorType.HUMAN.name());
        entry.setConfirmationSource(ConfirmationSource.MANUAL_API.name());
        entry.setHumanUserId(actor.getId());
        entry.setBeforeState(beforeState);
        entry.setAfterState(afterState);
        auditLogService.recordDomainEventRequired(entry);
    }

    static boolean violatesActiveLinkKey(Throwable failure) {
        java.util.Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(ACTIVE_LINK_UNIQUE_KEY)) {
                return true;
            }
        }
        return false;
    }

    private BusinessException alreadyLinked() {
        return BusinessException.of(409, "error.portalAdmin.engineerLink.alreadyLinked");
    }

    private PortalUserEngineerLinkDto toDto(PortalUserEngineerLink link, String tenantId) {
        Engineer engineer = link.getEngineerId() == null ? null : engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>()
                .eq(Engineer::getId, link.getEngineerId())
                .eq(Engineer::getTenantId, tenantId));
        return toDto(link, engineer);
    }

    private PortalUserEngineerLinkDto toDto(PortalUserEngineerLink link, Engineer engineer) {
        return PortalUserEngineerLinkDto.builder()
                .id(link.getId())
                .portalUserId(link.getPortalUserId())
                .engineerId(link.getEngineerId())
                .engineerName(engineer != null && Objects.equals(engineer.getId(), link.getEngineerId())
                        ? engineer.getFullName() : null)
                .active(link.getReleasedAt() == null)
                .linkedBy(link.getLinkedBy())
                .linkedAt(link.getLinkedAt())
                .releasedBy(link.getReleasedBy())
                .releasedAt(link.getReleasedAt())
                .build();
    }
}
