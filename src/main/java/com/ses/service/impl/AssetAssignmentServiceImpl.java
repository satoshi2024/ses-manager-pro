package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskMapper;
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
public class AssetAssignmentServiceImpl implements AssetAssignmentService {

    private final AssetAssignmentMapper assetAssignmentMapper;
    private final AssetMapper assetMapper;
    private final AssetEventService assetEventService;
    private final com.ses.mapper.DocumentLinkMapper documentLinkMapper;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final LifecycleTaskMapper lifecycleTaskMapper;
    private final LifecycleCaseMapper lifecycleCaseMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        assetAssignmentMapper.insert(assignment);

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
    @Transactional(rollbackFor = Exception.class)
    public AssetAssignment returnAssignment(Long assignmentId,
                                            LocalDate actualReturnDate,
                                            Long returnEvidenceDocId,
                                            String note,
                                            Long actorUserId) {
        if (actualReturnDate == null) {
            actualReturnDate = LocalDate.now();
        }

        // 1. 常に資産→貸与の順でロックする。返却と例外免除の順序を揃え、deadlockを防止する。
        AssetAssignment hint = assetAssignmentMapper.selectById(assignmentId);
        if (hint == null) {
            throw new BusinessException("指定された貸与データが見つかりません。");
        }
        Asset asset = assetMapper.selectByIdForUpdate(hint.getAssetId());
        if (asset == null) {
            throw new BusinessException("対象の資産が見つかりません。");
        }

        AssetAssignment assignment = assetAssignmentMapper.selectByIdForUpdate(assignmentId);
        if (assignment == null) {
            throw new BusinessException("指定された貸与データが見つかりません。");
        }
        if (!asset.getId().equals(assignment.getAssetId())) {
            throw new BusinessException(409, "貸与対象資産が変更されたため、返却を再実行してください。");
        }
        if (assignment.getActualReturnDate() != null || !List.of("ACTIVE", "OVERDUE").contains(assignment.getStatus())) {
            throw new BusinessException("この貸与は既に返却または免除済みです。");
        }
        if (StringUtils.hasText(note)) {
            note = StringUtils.hasText(assignment.getNote()) ? assignment.getNote() + " / " + note : note;
        } else {
            note = assignment.getNote();
        }

        // 2. 資産状態と貸与終端状態をともにCAS更新してから履歴を追記する。
        int assetRows = assetMapper.updateStatusWithCas(asset.getId(), "ASSIGNED", "IN_STOCK", asset.getVersion());
        if (assetRows != 1) {
            throw new BusinessException(409, "資産状態の更新が競合しました。再読み込みして返却してください。");
        }
        int assignmentRows = assetAssignmentMapper.markReturnedWithCas(
                assignment.getId(), actualReturnDate, returnEvidenceDocId, note, assignment.getVersion());
        if (assignmentRows != 1) {
            throw new BusinessException(409, "貸与状態の更新が競合しました。再読み込みして返却してください。");
        }
        assignment.setActualReturnDate(actualReturnDate);
        assignment.setReturnEvidenceDocId(returnEvidenceDocId);
        assignment.setStatus("RETURNED");
        assignment.setNote(note);

        // DocumentLink 連携（返却証跡）
        if (returnEvidenceDocId != null) {
            com.ses.entity.DocumentLink link = new com.ses.entity.DocumentLink();
            link.setDocumentId(returnEvidenceDocId);
            link.setTargetType("ASSET_ASSIGNMENT");
            link.setTargetId(assignment.getId());
            documentLinkMapper.insert(link);
        }

        // 3. イベント記録（状態更新が両方成功した後だけ記録する）
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
    @Transactional(rollbackFor = Exception.class)
    public AssetAssignment waiveAssignment(Long assignmentId,
                                            String reason,
                                            Long approvalRequestId,
                                            Long actorUserId) {
        // 返却と同じ資産→貸与の固定lock orderを使用する。
        AssetAssignment hint = assetAssignmentMapper.selectById(assignmentId);
        if (hint == null) {
            throw new BusinessException("指定された貸与データが見つかりません。");
        }
        Asset asset = assetMapper.selectByIdForUpdate(hint.getAssetId());
        if (asset == null) {
            throw new BusinessException("対象の資産が見つかりません。");
        }
        AssetAssignment assignment = assetAssignmentMapper.selectByIdForUpdate(assignmentId);
        if (assignment == null || !asset.getId().equals(assignment.getAssetId())) {
            throw new BusinessException(409, "貸与対象資産が変更されたため、免除を再実行してください。");
        }
        if (assignment.getActualReturnDate() != null || !List.of("ACTIVE", "OVERDUE").contains(assignment.getStatus())) {
            throw new BusinessException("この貸与は既に返却または免除済みです。");
        }
        assertWaiverApproval(assignment, approvalRequestId);
        if (!"ASSIGNED".equals(asset.getStatus())) {
            throw new BusinessException(409, "資産状態が貸与中ではないため、免除を完了できません。");
        }
        LocalDate waivedDate = LocalDate.now();
        String updatedNote = assignment.getNote();
        if (StringUtils.hasText(reason)) {
            updatedNote = StringUtils.hasText(updatedNote) ? updatedNote + " [例外免除: " + reason + "]" : "[例外免除: " + reason + "]";
        }
        int assetRows = assetMapper.updateStatusWithCas(asset.getId(), "ASSIGNED", "IN_STOCK", asset.getVersion());
        if (assetRows != 1) {
            throw new BusinessException(409, "資産状態の更新が競合しました。免除を再実行してください。");
        }
        int assignmentRows = assetAssignmentMapper.markWaivedWithCas(
                assignment.getId(), waivedDate, updatedNote, assignment.getVersion());
        if (assignmentRows != 1) {
            throw new BusinessException(409, "貸与状態の更新が競合しました。免除を再実行してください。");
        }
        assignment.setStatus("WAIVED");
        assignment.setActualReturnDate(waivedDate);
        assignment.setNote(updatedNote);

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

    private void assertWaiverApproval(AssetAssignment assignment, Long approvalRequestId) {
        if (approvalRequestId == null) {
            throw new BusinessException(400, "貸与免除には承認済みの例外申請が必要です。");
        }
        ApprovalRequest approval = approvalRequestMapper.selectByIdForUpdate(approvalRequestId);
        if (approval == null || !"LIFECYCLE_EXCEPTION".equals(approval.getRequestType())
                || approval.getStatus() == null || !"APPROVED".equalsIgnoreCase(approval.getStatus())) {
            throw new BusinessException(400, "有効な承認済み例外申請が見つかりません。");
        }
        boolean targetMatches = "ASSET_ASSIGNMENT".equals(approval.getTargetType())
                && assignment.getId().equals(approval.getTargetId());
        if (!targetMatches && "ENGINEER".equals(assignment.getAssigneeType())) {
            targetMatches = "ENGINEER".equals(approval.getTargetType())
                    && assignment.getAssigneeId().equals(approval.getTargetId());
        }
        if (!targetMatches && "LIFECYCLE_TASK".equals(approval.getTargetType()) && approval.getTargetId() != null) {
            var task = lifecycleTaskMapper.selectById(approval.getTargetId());
            var lcCase = task == null ? null : lifecycleCaseMapper.selectById(task.getCaseId());
            targetMatches = task != null && "RESIGN_ASSET_RETURN".equals(task.getTaskCode())
                    && lcCase != null && assignment.getAssigneeId().equals(lcCase.getEngineerId());
        }
        if (!targetMatches) {
            throw new BusinessException(400, "例外申請の対象が指定貸与と一致しません。");
        }
    }

    @Override
    public List<AssetAssignment> getActiveAssignmentsByAssignee(String assigneeType, Long assigneeId) {
        return assetAssignmentMapper.selectActiveByAssignee(assigneeType, assigneeId);
    }

    @Override
    public List<AssetAssignment> getAssignmentHistoryByAssetId(Long assetId) {
        return assetAssignmentMapper.selectList(new LambdaQueryWrapper<AssetAssignment>()
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
        return assetAssignmentMapper.selectPage(pageable, wrapper);
    }

    /** 貸与履歴は返却・移管の監査証跡であり、終端状態を含め論理削除しない。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteAssignment(Long id) {
        if (id == null || assetAssignmentMapper.selectById(id) == null) {
            return;
        }
        throw new BusinessException("資産貸与履歴は論理削除できません。返却・移管履歴を台帳上に保持してください。");
    }
}
