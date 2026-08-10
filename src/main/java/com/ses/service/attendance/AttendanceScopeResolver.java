package com.ses.service.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountingHistory;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OrganizationUnit;
import com.ses.mapper.AttendanceScopeMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerAccountingHistoryMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.UserOrganizationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 勤怠のHR法人scopeと日次/月次所属snapshotを同じ履歴規則で解決する。 */
@Service
@RequiredArgsConstructor
public class AttendanceScopeResolver {

    private final AttendanceScopeMapper attendanceScopeMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerAccountingHistoryMapper accountingHistoryMapper;
    private final EngineerAccountLinkMapper engineerAccountLinkMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final OrganizationUnitMapper organizationUnitMapper;

    public AttendanceScopeSnapshot requireSnapshot(Long engineerId, Long fallbackUserId, LocalDate asOf) {
        AttendanceScopeSnapshot snapshot = resolveSnapshot(engineerId, fallbackUserId, asOf);
        if (snapshot == null) {
            throw BusinessException.of(404, "error.attendance.scopeUnknown");
        }
        return snapshot;
    }

    public AttendanceScopeSnapshot resolveSnapshot(Long engineerId, Long fallbackUserId, LocalDate asOf) {
        if (engineerId == null || asOf == null) return null;
        Engineer engineer = engineerMapper.selectById(engineerId);
        if (engineer == null) return null;
        EngineerAccountingHistory history = accountingHistoryMapper.selectAt(engineerId, asOf);
        if (history != null && "UNKNOWN".equals(history.getOrganizationHistoryStatus())) {
            return null;
        }
        Long organizationId;
        if (history != null) {
            // 履歴行ありNULLは明示的な判定不能。現在所属・連携ユーザーへfallbackしない。
            organizationId = history.getOrganizationId();
            if (organizationId == null) return null;
        } else {
            organizationId = engineer.getOrganizationId();
        }
        if (history == null && organizationId == null) {
            Long linkedUserId = linkedUserId(engineerId, fallbackUserId);
            organizationId = linkedUserId == null ? null
                    : userOrganizationMapper.selectPrimaryOrganizationId(linkedUserId, asOf);
        }
        if (organizationId == null) return null;
        OrganizationUnit organization = organizationUnitMapper.selectAt(organizationId, asOf);
        if (organization == null || organization.getLegalEntityId() == null) return null;
        return new AttendanceScopeSnapshot(organization.getLegalEntityId(), organizationId);
    }

    public Set<Long> allowedHrEngineerIds(Long userId, LocalDate asOf) {
        Set<Long> legalEntityIds = allowedHrLegalEntityIds(userId, asOf);
        if (legalEntityIds == null || legalEntityIds.isEmpty()) return Set.of();
        List<Long> engineerIds = attendanceScopeMapper.selectEngineerIdsByLegalEntityIds(
                List.copyOf(legalEntityIds), asOf);
        return engineerIds == null ? Set.of() : Set.copyOf(engineerIds);
    }

    /** HRのlist/actionが月次snapshotへ適用するserver-side法人母集団。 */
    public Set<Long> allowedHrLegalEntityIds(Long userId, LocalDate asOf) {
        if (userId == null || asOf == null) return Set.of();
        List<Long> legalEntityIds = attendanceScopeMapper.selectLegalEntityIdsByUser(userId, asOf);
        return legalEntityIds == null ? Set.of() : Set.copyOf(legalEntityIds);
    }

    /** 全法人ID（管理者のpullが全法人のcursorを対象にするため、R5-P2-03）。 */
    public Set<Long> allLegalEntityIds() {
        List<Long> legalEntityIds = attendanceScopeMapper.selectAllLegalEntityIds();
        return legalEntityIds == null ? Set.of() : Set.copyOf(legalEntityIds);
    }

    private Long linkedUserId(Long engineerId, Long fallbackUserId) {
        EngineerAccountLink link = engineerAccountLinkMapper.selectByEngineerId(engineerId);
        return link != null && link.getSysUserId() != null ? link.getSysUserId() : fallbackUserId;
    }
}
