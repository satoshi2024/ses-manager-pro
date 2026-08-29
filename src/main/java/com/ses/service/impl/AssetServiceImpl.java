package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.constant.AssetStatusPolicy;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import com.ses.service.AssetEventService;
import com.ses.service.AssetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetServiceImpl implements AssetService {

    private final AssetMapper assetMapper;
    private final AssetAssignmentMapper assetAssignmentMapper;
    private final AssetEventService assetEventService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Asset createAsset(Asset asset, Long actorUserId) {
        if (!StringUtils.hasText(asset.getAssetTag())) {
            throw new BusinessException("資産管理タグは必須です。");
        }
        if (!StringUtils.hasText(asset.getAssetName())) {
            throw new BusinessException("資産名称は必須です。");
        }
        if (!StringUtils.hasText(asset.getCategory())) {
            throw new BusinessException("資産区分は必須です。");
        }

        // タグの一意性確認
        Asset existing = getByAssetTag(asset.getAssetTag().trim());
        if (existing != null) {
            throw new BusinessException("資産管理タグ「" + asset.getAssetTag() + "」は既に使用されています。");
        }

        asset.setAssetTag(asset.getAssetTag().trim());
        asset.setStatus(StringUtils.hasText(asset.getStatus())
                ? AssetStatusPolicy.normalize(asset.getStatus()) : AssetStatusPolicy.IN_STOCK);
        assertAllowedStatus(asset.getStatus());
        assetMapper.insert(asset);

        assetEventService.recordEvent(
                asset.getId(),
                "CREATED",
                actorUserId,
                null,
                null,
                null,
                asset.getStatus(),
                null,
                "資産を新規登録しました（タグ: " + asset.getAssetTag() + ", 名称: " + asset.getAssetName() + "）",
                null
        );

        return asset;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Asset updateAsset(Asset asset, Long actorUserId) {
        if (asset.getId() == null) {
            throw new BusinessException("資産IDが指定されていません。");
        }
        Asset current = assetMapper.selectById(asset.getId());
        if (current == null) {
            throw new BusinessException("指定された資産が見つかりません。");
        }

        // タグ変更時の重複確認
        if (StringUtils.hasText(asset.getAssetTag()) && !asset.getAssetTag().trim().equals(current.getAssetTag())) {
            Asset tagHolder = getByAssetTag(asset.getAssetTag().trim());
            if (tagHolder != null && !tagHolder.getId().equals(asset.getId())) {
                throw new BusinessException("資産管理タグ「" + asset.getAssetTag() + "」は既に使用されています。");
            }
            current.setAssetTag(asset.getAssetTag().trim());
        }

        if (StringUtils.hasText(asset.getAssetName())) {
            current.setAssetName(asset.getAssetName());
        }
        if (StringUtils.hasText(asset.getCategory())) {
            current.setCategory(asset.getCategory());
        }
        current.setSerialNo(asset.getSerialNo());
        current.setOwnerCompanyId(asset.getOwnerCompanyId());
        current.setLocation(asset.getLocation());
        current.setPurchaseDate(asset.getPurchaseDate());
        current.setPurchasePrice(asset.getPurchasePrice());
        current.setWarrantyExpiry(asset.getWarrantyExpiry());
        current.setLeaseExpiry(asset.getLeaseExpiry());
        current.setNote(asset.getNote());

        assetMapper.updateById(current);

        assetEventService.recordEvent(
                current.getId(),
                "UPDATED",
                actorUserId,
                null,
                null,
                current.getStatus(),
                current.getStatus(),
                null,
                "資産基本情報を更新しました",
                null
        );

        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Asset changeStatus(Long assetId, String toStatus, String reason, Long actorUserId, Long evidenceDocId) {
        String normalizedToStatus = AssetStatusPolicy.normalize(toStatus);
        assertAllowedStatus(normalizedToStatus);

        Asset asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null) {
            throw new BusinessException("指定された資産が見つかりません。");
        }

        String fromStatus = asset.getStatus();
        if (fromStatus.equals(normalizedToStatus)) {
            return asset;
        }

        assertAllowedTransition(fromStatus, normalizedToStatus);

        int updated = assetMapper.updateStatusWithCas(assetId, fromStatus, normalizedToStatus, asset.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "資産情報が他で更新されました。再読み込みしてください。");
        }

        assetEventService.recordEvent(
                assetId,
                "STATUS_CHANGED",
                actorUserId,
                null,
                null,
                fromStatus,
                normalizedToStatus,
                evidenceDocId,
                "ステータスを変更しました: " + fromStatus + " -> " + normalizedToStatus + (StringUtils.hasText(reason) ? " (" + reason + ")" : ""),
                null
        );

        return assetMapper.selectById(assetId);
    }

    private void assertAllowedStatus(String status) {
        if (!AssetStatusPolicy.ALLOWED_VALUES.contains(status)) {
            throw new BusinessException(400, "資産ステータスが不正です。許可値: " + AssetStatusPolicy.ALLOWED_VALUES);
        }
    }

    private void assertAllowedTransition(String fromStatus, String toStatus) {
        if (!AssetStatusPolicy.isAllowedGenericTransition(fromStatus, toStatus)) {
            throw new BusinessException(400,
                    "資産ステータス遷移が許可されていません: " + fromStatus + " -> " + toStatus
                            + "。貸与・返却は専用の貸与サービスを使用してください。");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Asset disposeAsset(Long assetId, String reason, Long actorUserId, Long evidenceDocId) {
        Asset asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null) {
            throw new BusinessException("指定された資産が見つかりません。");
        }
        if ("ASSIGNED".equals(asset.getStatus())) {
            throw new BusinessException("貸与中の資産は廃棄できません。先に返却処理を行ってください。");
        }

        String fromStatus = asset.getStatus();
        if (AssetStatusPolicy.DISPOSED.equals(fromStatus)) {
            return asset;
        }
        assertAllowedTransition(fromStatus, AssetStatusPolicy.DISPOSED);
        int updated = assetMapper.updateStatusWithCas(assetId, fromStatus, "DISPOSED", asset.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "資産情報が他で更新されました。再読み込みしてください。");
        }

        assetEventService.recordEvent(
                assetId,
                "DISPOSED",
                actorUserId,
                null,
                null,
                fromStatus,
                "DISPOSED",
                evidenceDocId,
                "資産を廃棄処分としました: " + (StringUtils.hasText(reason) ? reason : "通常廃棄"),
                null
        );

        return assetMapper.selectById(assetId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Asset reportLost(Long assetId, String incidentDetails, Long actorUserId, Long evidenceDocId) {
        Asset asset = assetMapper.selectByIdForUpdate(assetId);
        if (asset == null) {
            throw new BusinessException("指定された資産が見つかりません。");
        }

        String fromStatus = asset.getStatus();
        if (AssetStatusPolicy.LOST.equals(fromStatus)) {
            return asset;
        }
        assertAllowedTransition(fromStatus, AssetStatusPolicy.LOST);
        int updated = assetMapper.updateStatusWithCas(assetId, fromStatus, AssetStatusPolicy.LOST, asset.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "資産情報が他で更新されました。再読み込みしてください。");
        }

        assetEventService.recordEvent(
                assetId,
                "REPORTED_LOST",
                actorUserId,
                null,
                null,
                fromStatus,
                "LOST",
                evidenceDocId,
                "資産の紛失が報告されました: " + (StringUtils.hasText(incidentDetails) ? incidentDetails : "詳細未入力"),
                incidentDetails
        );

        return assetMapper.selectById(assetId);
    }

    @Override
    public IPage<Asset> searchAssets(int page, int size, String keyword, String category, String status, Long ownerCompanyId) {
        return searchAssetsScoped(page, size, keyword, category, status, ownerCompanyId, null);
    }

    @Override
    public IPage<Asset> searchAssetsScoped(int page, int size, String keyword, String category, String status,
                                           Long ownerCompanyId, List<Long> accessibleAssetIds) {
        Page<Asset> pageable = new Page<>(page, size);
        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Asset::getAssetTag, kw)
                    .or().like(Asset::getAssetName, kw)
                    .or().like(Asset::getSerialNo, kw)
                    .or().like(Asset::getLocation, kw));
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(Asset::getCategory, category);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Asset::getStatus, status);
        }
        if (ownerCompanyId != null) {
            wrapper.eq(Asset::getOwnerCompanyId, ownerCompanyId);
        }
        if (accessibleAssetIds != null) {
            if (accessibleAssetIds.isEmpty()) {
                wrapper.eq(Asset::getId, -1L);
            } else {
                wrapper.in(Asset::getId, accessibleAssetIds);
            }
        }

        wrapper.orderByDesc(Asset::getId);
        return assetMapper.selectPage(pageable, wrapper);
    }

    @Override
    public Asset getById(Long assetId) {
        return assetMapper.selectById(assetId);
    }

    @Override
    public Asset getByAssetTag(String assetTag) {
        if (!StringUtils.hasText(assetTag)) {
            return null;
        }
        return assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getAssetTag, assetTag.trim()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void softDeleteAsset(Long assetId) {
        Asset current = assetMapper.selectByIdForUpdate(assetId);
        if (current == null) {
            return;
        }
        Long activeCount = assetAssignmentMapper.selectCount(new LambdaQueryWrapper<AssetAssignment>()
                .eq(AssetAssignment::getAssetId, assetId)
                .in(AssetAssignment::getStatus, "ACTIVE", "OVERDUE")
                .isNull(AssetAssignment::getActualReturnDate));
        if (activeCount != null && activeCount > 0) {
            throw new BusinessException(
                    "未返却貸与が存在する資産を論理削除することはできません（AS-R1.5(a)）。先に貸与を返却またはWAIVED処理してください。");
        }
        if (!"DISPOSED".equals(current.getStatus())) {
            throw new BusinessException("資産の論理削除はDISPOSED状態でのみ実行できます。廃棄は削除ではなく状態遷移として記録してください。");
        }
        assetMapper.deleteById(assetId);
        log.info("Asset soft-deleted: assetId={}", assetId);
    }
}
