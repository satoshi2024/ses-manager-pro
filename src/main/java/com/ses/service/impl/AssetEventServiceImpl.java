package com.ses.service.impl;

import com.ses.entity.AssetEvent;
import com.ses.common.audit.ActorAttribution;
import com.ses.mapper.AssetEventMapper;
import com.ses.service.AssetEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetEventServiceImpl implements AssetEventService {

    private final AssetEventMapper assetEventMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public AssetEvent recordEvent(Long assetId,
                                  String eventType,
                                  Long actorUserId,
                                  String assigneeType,
                                  Long assigneeId,
                                  String fromStatus,
                                  String toStatus,
                                  Long evidenceDocId,
                                  String eventSummary,
                                  String detailsJson) {
        AssetEvent event = AssetEvent.builder()
                .assetId(assetId)
                .eventType(eventType)
                .eventTime(LocalDateTime.now())
                .actorUserId(actorUserId)
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .evidenceDocId(evidenceDocId)
                .eventSummary(eventSummary)
                .detailsJson(detailsJson)
                .build();
        assetEventMapper.insert(event);
        log.info("Asset event recorded: assetId={}, type={}, from={}, to={}, actor={}",
                assetId, eventType, fromStatus, toStatus, actorUserId);
        return event;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public AssetEvent recordExternalAccountConfirmation(Long referenceId,
                                                         String eventType,
                                                         String beforeState,
                                                         String afterState,
                                                         ActorAttribution attribution,
                                                         String eventSummary,
                                                         String detailsJson) {
        if (attribution == null) {
            throw new IllegalArgumentException("actor attribution is required");
        }
        AssetEvent event = AssetEvent.builder()
                .referenceType("EXTERNAL_ACCOUNT_REFERENCE")
                .referenceId(referenceId)
                .eventType(eventType)
                .eventTime(LocalDateTime.now())
                .actorUserId(attribution.humanUserId())
                .actorType(attribution.actorType().name())
                .confirmationSource(attribution.confirmationSource().name())
                .humanUserId(attribution.humanUserId())
                .fromStatus(beforeState)
                .toStatus(afterState)
                .eventSummary(eventSummary)
                .detailsJson(detailsJson)
                .correlationId(attribution.correlationId())
                .idempotencyKey(attribution.idempotencyKey())
                .build();
        assetEventMapper.insert(event);
        return event;
    }

    @Override
    public List<AssetEvent> getEventsByAssetId(Long assetId) {
        return assetEventMapper.selectByAssetId(assetId);
    }
}
