package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.service.AssetAssignmentService;
import com.ses.service.AssetEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetAssignmentServiceImpl extends ServiceImpl<AssetAssignmentMapper, AssetAssignment> implements AssetAssignmentService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final AssetMapper assetMapper;
    private final AssetEventService assetEventService;
    private final com.ses.mapper.DocumentLinkMapper documentLinkMapper;

    @Override
    @Transactional
    public AssetAssignment createAssignment(Long assetId,
                                            String assigneeType,
                                            Long assigneeId,
                                            LocalDate startDate,
                                            LocalDate expectedReturnDate,
                                            Long handoverEvidenceDocId,
                                            String note,
                                            Long actorUserId) {
        if (assetId == null) {
            throw new BusinessException("対象資産IDは必須です。");
        }
        if (!StringUtils.hasText(assigneeType) || assigneeId == null) {
            throw new BusinessException("貸与先区分および貸与先IDは必須です。");
        }
        if (startDate == null) {
            startDate = LocalDate.now();
        }
        if (expectedReturnDate != null && expectedReturnDate.isBefore(startDate)) {
            throw new BusinessException("返却予定日は貸与開始日以降の日付を指定してください。");
        }

        // 1. 行ロックにより対象資産を取得
        Asset asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null) {
            throw new BusinessException("指定された資産が見つかりません。");
        }
        if (!"IN_STOCK".equals(asset.getStatus())) {
            throw new BusinessException("指定された資産は現在貸出可能（保管中）ではありません。現在の状態: " + asset.getStatus());
        }

        // 2. 期間重複判定
        int overlappingCount = assetAssignmentMapper.countOverlappingAssignments(
                assetId, startDate, expectedReturnDate, null);
        if (overlappingCount > 0) {
            throw new BusinessException(409, "指定された期間には既に有効な貸与が存在します。重複貸与は禁止されています。");
        }

        // 3. 貸与レコード作成
        AssetAssignment assignment = AssetAssignment.builder()
                .assetId(assetId)
                .assigneeType(assigneeType)
                .assigneeId(assigneeId)
                .startDate(startDate)
                .expectedReturnDate(expectedReturnDate)
                .actualReturnDate(null)
                .handoverEvidenceDocId(handoverEvidenceDocId)
                .status("ACTIVE")
                .note(note)
                .createdBy(actorUserId)
                .build();
        save(assignment);

        // DocumentLink 連携（受渡証跡）
        if (handoverEvidenceDocId != null) {
            com.ses.entity.DocumentLink link = new com.ses.entity.DocumentLink();
            link.setDocumentId(handoverEvidenceDocId);
            link.setTargetType("ASSET_ASSIGNMENT");
            link.setTargetId(assignment.getId());
            documentLinkMapper.insert(link);
        }

        // 4. 資産ステータスを ASSIGNED に更新
        int updated = assetMapper.updateStatusWithCas(assetId, "IN_STOCK", "ASSIGNED", asset.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "資産ステータスの更新に失敗しました（並行更新競合）。");
        }

        // 5. イベント記録
        assetEventService.recordEvent(
                assetId,
                "ASSIGNED",
                actorUserId,
                assigneeType,
                assigneeId,
                "IN_STOCK",
                "ASSIGNED",
                handoverEvidenceDocId,
                "資産を貸与しました（貸与先: " + assigneeType + "#" + assigneeId + ", 開始日: " + startDate + "）",
                note
        );

        log.info("Asset assigned successfully: assignmentId={}, assetId={}, assignee={}/{}",
                assignment.getId(), assetId, assigneeType, assigneeId);
        return assignment;
    }

    @Override
    @Transactional
    public AssetAssignment returnAssignment(Long assignmentId,
                                            LocalDate actualReturnDate,
                                            Long returnEvidenceDocId,
                                            String note,
                                            Long actorUserId) {
        AssetAssignment assignment = getById(assignmentId);
        if (assignment == null) {
            throw new BusinessException("指定された貸与データが見つかりません。");
        }
        if (assignment.getActualReturnDate() != null || "RETURNED".equals(assignment.getStatus())) {
            throw new BusinessException("この貸与は既に返却完了しています。");
        }

        if (actualReturnDate == null) {
            actualReturnDate = LocalDate.now();
        }

        // 1. 資産を行ロック
        Asset asset = assetMapper.selectByIdForUpdate(assignment.getAssetId());
        if (asset == null) {
            throw new BusinessException("対象の資産が見つかりません。");
        }

        // 2. 貸与レコード更新
        assignment.setActualReturnDate(actualReturnDate);
        assignment.setReturnEvidenceDocId(returnEvidenceDocId);
        assignment.setStatus("RETURNED");
        if (StringUtils.hasText(note)) {
            assignment.setNote(StringUtils.hasText(assignment.getNote()) ? assignment.getNote() + " / " + note : note);
        }
        updateById(assignment);

        // DocumentLink 連携（返却証跡）
        if (returnEvidenceDocId != null) {
            com.ses.entity.DocumentLink link = new com.ses.entity.DocumentLink();
            link.setDocumentId(returnEvidenceDocId);
            link.setTargetType("ASSET_ASSIGNMENT");
            link.setTargetId(assignment.getId());
            documentLinkMapper.insert(link);
        }

        // 3. 資産ステータスを IN_STOCK に復帰（現在 ASSIGNED の場合のみ）
        if ("ASSIGNED".equals(asset.getStatus())) {
            assetMapper.updateStatusWithCas(asset.getId(), "ASSIGNED", "IN_STOCK", asset.getVersion());
        }

        // 4. イベント記録
        assetEventService.recordEvent(
                asset.getId(),
                "RETURNED",
                actorUserId,
                assignment.getAssigneeType(),
                assignment.getAssigneeId(),
                "ASSIGNED",
                "IN_STOCK",
                returnEvidenceDocId,
                "資産が返却されました（返却日: " + actualReturnDate + "）",
                note
        );

        log.info("Asset returned successfully: assignmentId={}, assetId={}, actualReturnDate={}",
                assignmentId, asset.getId(), actualReturnDate);
        return assignment;
    }

    @Override
    @Transactional
    public AssetAssignment waiveAssignment(Long assignmentId,
                                            String reason,
                                            Long approvalRequestId,
                                            Long actorUserId) {
        AssetAssignment assignment = getById(assignmentId);
        if (assignment == null) {
            throw new BusinessException("指定された貸与データが見つかりません。");
        }
        if ("RETURNED".equals(assignment.getStatus()) || "WAIVED".equals(assignment.getStatus())) {
            throw new BusinessException("この貸与は既に返却または免除済みです。");
        }

        assignment.setStatus("WAIVED");
        assignment.setActualReturnDate(LocalDate.now());
        if (StringUtils.hasText(reason)) {
            assignment.setNote(StringUtils.hasText(assignment.getNote()) ? assignment.getNote() + " [例外免除: " + reason + "]" : "[例外免除: " + reason + "]");
        }
        updateById(assignment);

        Asset asset = assetMapper.selectByIdForUpdate(assignment.getAssetId());
        if (asset != null && "ASSIGNED".equals(asset.getStatus())) {
            assetMapper.updateStatusWithCas(asset.getId(), "ASSIGNED", "IN_STOCK", asset.getVersion());
        }

        assetEventService.recordEvent(
                assignment.getAssetId(),
                "WAIVED",
                actorUserId,
                assignment.getAssigneeType(),
                assignment.getAssigneeId(),
                "ASSIGNED",
                "IN_STOCK",
                null,
                "貸与返却が例外免除されました (承認申請ID: " + approvalRequestId + ", 理由: " + reason + ")",
                reason
        );

        return assignment;
    }

    @Override
    public List<AssetAssignment> getActiveAssignmentsByAssignee(String assigneeType, Long assigneeId) {
        return assetAssignmentMapper.selectActiveByAssignee(assigneeType, assigneeId);
    }

    @Override
    public List<AssetAssignment> getAssignmentHistoryByAssetId(Long assetId) {
        return list(new LambdaQueryWrapper<AssetAssignment>()
                .eq(AssetAssignment::getAssetId, assetId)
                .orderByDesc(AssetAssignment::getStartDate)
                .orderByDesc(AssetAssignment::getId));
    }

    @Override
    public IPage<AssetAssignment> searchAssignments(int page, int size, String assigneeType, Long assigneeId, String status) {
        Page<AssetAssignment> pageable = new Page<>(page, size);
        LambdaQueryWrapper<AssetAssignment> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(assigneeType)) {
            wrapper.eq(AssetAssignment::getAssigneeType, assigneeType);
        }
        if (assigneeId != null) {
            wrapper.eq(AssetAssignment::getAssigneeId, assigneeId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssetAssignment::getStatus, status);
        }
        wrapper.orderByDesc(AssetAssignment::getId);
        return page(pageable, wrapper);
    }
}
