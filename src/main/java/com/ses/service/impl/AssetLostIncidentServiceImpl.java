package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.constant.AssetStatusPolicy;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Asset;
import com.ses.entity.AssetLostIncident;
import com.ses.entity.DocumentLink;
import com.ses.mapper.AssetLostIncidentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AssetAlertService;
import com.ses.service.AssetEventService;
import com.ses.service.AssetLostIncidentService;
import com.ses.service.AssetScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 紛失資産インシデントの台帳・対応状態サービス実装。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetLostIncidentServiceImpl implements AssetLostIncidentService {

    private static final String LINK_TARGET_TYPE = "ASSET_LOST_INCIDENT";
    private static final Set<String> REMOTE_WIPE_STATUSES = Set.of(
            "NOT_REQUESTED", "REQUESTED", "EXECUTED", "CONFIRMED", "FAILED", "UNKNOWN");
    private static final Set<String> INSURANCE_CLAIM_STATUSES = Set.of(
            "NOT_APPLIED", "APPLIED", "SETTLED", "REJECTED");

    private final AssetLostIncidentMapper assetLostIncidentMapper;
    private final AssetMapper assetMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentMapper documentMapper;
    private final SysUserMapper sysUserMapper;
    private final AssetEventService assetEventService;
    private final AssetAlertService assetAlertService;
    private final AssetScopeService assetScopeService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetLostIncident createInitial(Long assetId, String incidentDetails, Long reporterUserId,
                                           Long evidenceDocumentId) {
        Asset asset = getLostAsset(assetId);
        AssetLostIncident existing = assetLostIncidentMapper.selectLatestByAssetId(assetId);
        if (existing != null) {
            return enrich(existing);
        }

        AssetLostIncident incident = AssetLostIncident.builder()
                .assetId(assetId)
                .reportedAt(LocalDateTime.now())
                .reportedBy(reporterUserId)
                .incidentDetails(incidentDetails)
                .remoteWipeStatus("NOT_REQUESTED")
                .insuranceClaimStatus("NOT_APPLIED")
                .build();
        assetLostIncidentMapper.insert(incident);
        linkDocuments(incident.getId(), assetId,
                evidenceDocumentId == null ? List.of() : List.of(evidenceDocumentId), reporterUserId);

        // AssetServiceImplのLOST遷移と同一transaction内でoutboxへ登録する。
        assetAlertService.notifyLostAssetIncident(asset, incident);
        log.warn("紛失インシデントを記録しました: assetId={}, incidentId={}", assetId, incident.getId());
        return enrich(incident);
    }

    @Override
    public AssetLostIncident getByAssetId(Long assetId) {
        AssetLostIncident incident = assetLostIncidentMapper.selectLatestByAssetId(assetId);
        return incident == null ? null : enrich(incident);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetLostIncident update(Long assetId, String incidentDetails, String remoteWipeStatus,
                                    LocalDateTime remoteWipeRequestedAt, LocalDateTime remoteWipeExecutedAt,
                                    LocalDateTime remoteWipeConfirmedAt, String policeReportNumber,
                                    String insuranceClaimStatus, LocalDateTime insuranceClaimedAt,
                                    List<Long> documentIds, Long actorUserId) {
        Asset asset = getLostAsset(assetId);
        AssetLostIncident stored = assetLostIncidentMapper.selectLatestByAssetId(assetId);
        if (stored == null) {
            throw new BusinessException(404, "紛失インシデントが見つかりません。先に紛失報告を登録してください。");
        }
        AssetLostIncident incident = assetLostIncidentMapper.selectByIdForUpdate(stored.getId());
        if (incident == null) {
            throw new BusinessException(404, "紛失インシデントが見つかりません。");
        }

        boolean changed = false;
        if (incidentDetails != null) {
            incident.setIncidentDetails(incidentDetails);
            changed = true;
        }
        if (remoteWipeStatus != null) {
            String normalized = normalize(remoteWipeStatus);
            assertAllowed(REMOTE_WIPE_STATUSES, normalized, "リモートワイプ状態");
            if (!normalized.equals(incident.getRemoteWipeStatus())) {
                incident.setRemoteWipeStatus(normalized);
                changed = true;
            }
            LocalDateTime now = LocalDateTime.now();
            if ("REQUESTED".equals(normalized) && incident.getRemoteWipeRequestedAt() == null) {
                incident.setRemoteWipeRequestedAt(now);
                changed = true;
            }
            if ("EXECUTED".equals(normalized) && incident.getRemoteWipeExecutedAt() == null) {
                incident.setRemoteWipeExecutedAt(now);
                changed = true;
            }
            if ("CONFIRMED".equals(normalized) && incident.getRemoteWipeConfirmedAt() == null) {
                incident.setRemoteWipeConfirmedAt(now);
                changed = true;
            }
        }
        if (remoteWipeRequestedAt != null) {
            incident.setRemoteWipeRequestedAt(remoteWipeRequestedAt);
            changed = true;
        }
        if (remoteWipeExecutedAt != null) {
            incident.setRemoteWipeExecutedAt(remoteWipeExecutedAt);
            changed = true;
        }
        if (remoteWipeConfirmedAt != null) {
            incident.setRemoteWipeConfirmedAt(remoteWipeConfirmedAt);
            changed = true;
        }
        if (policeReportNumber != null) {
            incident.setPoliceReportNumber(StringUtils.hasText(policeReportNumber)
                    ? policeReportNumber.trim() : null);
            changed = true;
        }
        if (insuranceClaimStatus != null) {
            String normalized = normalize(insuranceClaimStatus);
            assertAllowed(INSURANCE_CLAIM_STATUSES, normalized, "保険申請状態");
            if (!normalized.equals(incident.getInsuranceClaimStatus())) {
                incident.setInsuranceClaimStatus(normalized);
                changed = true;
            }
            if ("APPLIED".equals(normalized) && incident.getInsuranceClaimedAt() == null) {
                incident.setInsuranceClaimedAt(LocalDateTime.now());
                changed = true;
            }
        }
        if (insuranceClaimedAt != null) {
            incident.setInsuranceClaimedAt(insuranceClaimedAt);
            changed = true;
        }

        assertTimeline(incident);

        linkDocuments(incident.getId(), assetId, documentIds, actorUserId);
        if (changed) {
            assetLostIncidentMapper.updateById(incident);
            assetEventService.recordEvent(
                    assetId,
                    "LOST_INCIDENT_UPDATED",
                    actorUserId,
                    null,
                    null,
                    AssetStatusPolicy.LOST,
                    AssetStatusPolicy.LOST,
                    null,
                    "紛失インシデント対応情報を更新しました",
                    null);
        }
        return enrich(incident);
    }

    private Asset getLostAsset(Long assetId) {
        if (assetId == null) {
            throw new BusinessException(400, "資産IDは必須です。");
        }
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null) {
            throw new BusinessException(404, "指定された資産が見つかりません。");
        }
        if (!AssetStatusPolicy.LOST.equals(asset.getStatus())) {
            throw new BusinessException(409, "紛失インシデントはLOST状態の資産にだけ登録できます。");
        }
        return asset;
    }

    private void linkDocuments(Long incidentId, Long assetId, List<Long> documentIds, Long actorUserId) {
        if (documentIds == null) {
            return;
        }
        for (Long documentId : documentIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            if (documentMapper.selectById(documentId) == null) {
                throw new BusinessException(404, "関連文書が見つかりません: " + documentId);
            }
            if (!canLinkDocument(documentId, assetId, actorUserId)) {
                throw new BusinessException(403,
                        "関連文書が現在の資産・組織スコープ外です。既存の認可済み文書だけを紛失インシデントへ関連付けできます。");
            }
            DocumentLink existing = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                    .eq(DocumentLink::getDocumentId, documentId)
                    .eq(DocumentLink::getTargetType, LINK_TARGET_TYPE)
                    .eq(DocumentLink::getTargetId, incidentId));
            if (existing == null) {
                DocumentLink link = new DocumentLink();
                link.setDocumentId(documentId);
                link.setTargetType(LINK_TARGET_TYPE);
                link.setTargetId(incidentId);
                documentLinkMapper.insert(link);
            }
        }
    }

    /**
     * 非管理者の文書ID推測による別法人・別組織文書の横取りを防ぐ。
     * 未リンク文書は認可母集団を導出できないため、管理者/HR以外からは拒否する。
     */
    private boolean canLinkDocument(Long documentId, Long assetId, Long actorUserId) {
        String role;
        if (actorUserId != null) {
            var actor = sysUserMapper.selectById(actorUserId);
            role = actor == null ? null : actor.getRole();
        } else {
            role = SecurityUtils.currentRole();
        }
        return role != null
                && assetScopeService.isAccessible(assetId, role, actorUserId)
                && assetScopeService.isAccessibleByDocumentLink(documentId, role, actorUserId);
    }

    private AssetLostIncident enrich(AssetLostIncident incident) {
        incident.setRelatedDocumentIds(documentLinkMapper.findDocumentIdsByTarget(
                LINK_TARGET_TYPE, incident.getId()));
        return incident;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private void assertAllowed(Set<String> allowed, String value, String label) {
        if (!allowed.contains(value)) {
            throw new BusinessException(400, label + "が不正です。許可値: " + allowed);
        }
    }

    private void assertTimeline(AssetLostIncident incident) {
        LocalDateTime now = LocalDateTime.now();
        if (incident.getReportedAt() != null && incident.getReportedAt().isAfter(now)) {
            throw new BusinessException(400, "インシデント起票日時を未来に設定できません。");
        }
        if (incident.getRemoteWipeRequestedAt() != null && incident.getRemoteWipeRequestedAt().isAfter(now)
                || incident.getRemoteWipeExecutedAt() != null && incident.getRemoteWipeExecutedAt().isAfter(now)
                || incident.getRemoteWipeConfirmedAt() != null && incident.getRemoteWipeConfirmedAt().isAfter(now)
                || incident.getInsuranceClaimedAt() != null && incident.getInsuranceClaimedAt().isAfter(now)) {
            throw new BusinessException(400, "対応日時を未来に設定できません。");
        }
        if (incident.getRemoteWipeRequestedAt() != null && incident.getRemoteWipeExecutedAt() != null
                && incident.getRemoteWipeRequestedAt().isAfter(incident.getRemoteWipeExecutedAt())) {
            throw new BusinessException(400, "リモートワイプ実施日時は要求日時以降である必要があります。");
        }
        if (incident.getRemoteWipeExecutedAt() != null && incident.getRemoteWipeConfirmedAt() != null
                && incident.getRemoteWipeExecutedAt().isAfter(incident.getRemoteWipeConfirmedAt())) {
            throw new BusinessException(400, "リモートワイプ確認日時は実施日時以降である必要があります。");
        }
    }
}
