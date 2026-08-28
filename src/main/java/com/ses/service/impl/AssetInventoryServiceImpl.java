package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Asset;
import com.ses.entity.AssetInventoryItem;
import com.ses.entity.AssetInventoryRun;
import com.ses.mapper.AssetInventoryItemMapper;
import com.ses.mapper.AssetInventoryRunMapper;
import com.ses.mapper.AssetMapper;
import com.ses.service.AssetEventService;
import com.ses.service.AssetInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssetInventoryServiceImpl extends ServiceImpl<AssetInventoryRunMapper, AssetInventoryRun> implements AssetInventoryService {

    private final AssetInventoryRunMapper assetInventoryRunMapper;
    private final AssetInventoryItemMapper assetInventoryItemMapper;
    private final AssetMapper assetMapper;
    private final AssetEventService assetEventService;

    @Override
    @Transactional
    public AssetInventoryRun startInventoryRun(String inventoryCode,
                                               String title,
                                               LocalDate targetDate,
                                               Long actorUserId) {
        if (!StringUtils.hasText(inventoryCode)) {
            throw new BusinessException("棚卸しコードは必須です。");
        }
        if (!StringUtils.hasText(title)) {
            throw new BusinessException("棚卸し名称は必須です。");
        }
        if (targetDate == null) {
            targetDate = LocalDate.now();
        }

        AssetInventoryRun existing = getOne(new LambdaQueryWrapper<AssetInventoryRun>()
                .eq(AssetInventoryRun::getInventoryCode, inventoryCode.trim()), false);
        if (existing != null) {
            throw new BusinessException("棚卸しコード「" + inventoryCode + "」は既に使用されています。");
        }

        // 1. 保管中・貸与中・修理中の全有効資産を取得
        List<Asset> activeAssets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .ne(Asset::getStatus, "DISPOSED")
                .eq(Asset::getDeletedFlag, 0));

        AssetInventoryRun run = AssetInventoryRun.builder()
                .inventoryCode(inventoryCode.trim())
                .title(title)
                .targetDate(targetDate)
                .status("IN_PROGRESS")
                .totalAssets(activeAssets.size())
                .matchedCount(0)
                .discrepancyCount(0)
                .missingCount(0)
                .conductedBy(actorUserId)
                .build();
        save(run);

        // 2. 明細レコード生成
        for (Asset a : activeAssets) {
            AssetInventoryItem item = AssetInventoryItem.builder()
                    .inventoryRunId(run.getId())
                    .assetId(a.getId())
                    .expectedStatus(a.getStatus())
                    .expectedLocation(a.getLocation())
                    .discrepancyType("UNCHECKED")
                    .build();
            assetInventoryItemMapper.insert(item);
        }

        log.info("Inventory run started: code={}, totalAssets={}", inventoryCode, activeAssets.size());
        return run;
    }

    @Override
    @Transactional
    public AssetInventoryItem recordItemCheck(Long itemId,
                                              String observedStatus,
                                              String observedLocation,
                                              String discrepancyType,
                                              String discrepancyReason,
                                              String resolutionAction,
                                              Long actorUserId) {
        AssetInventoryItem item = assetInventoryItemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException("指定された棚卸し明細が見つかりません。");
        }

        AssetInventoryRun run = getById(item.getInventoryRunId());
        if (run != null && "COMPLETED".equals(run.getStatus())) {
            throw new BusinessException("完了済みの棚卸し明細は変更できません。");
        }

        if (!StringUtils.hasText(discrepancyType)) {
            discrepancyType = "MATCH";
        }

        item.setObservedStatus(observedStatus);
        item.setObservedLocation(observedLocation);
        item.setDiscrepancyType(discrepancyType);
        item.setDiscrepancyReason(discrepancyReason);
        item.setResolutionAction(resolutionAction);
        item.setCheckedBy(actorUserId);
        item.setCheckedAt(LocalDateTime.now());
        assetInventoryItemMapper.updateById(item);

        return item;
    }

    @Override
    @Transactional
    public AssetInventoryRun completeInventoryRun(Long runId, Long actorUserId) {
        AssetInventoryRun run = getById(runId);
        if (run == null) {
            throw new BusinessException("指定された棚卸し計画が見つかりません。");
        }
        if ("COMPLETED".equals(run.getStatus())) {
            throw new BusinessException("既に完了済みの棚卸しです。");
        }

        // 明細の集計
        List<AssetInventoryItem> items = assetInventoryItemMapper.selectByRunId(runId);
        int matched = 0;
        int discrepancy = 0;
        int missing = 0;

        for (AssetInventoryItem item : items) {
            String type = item.getDiscrepancyType();
            if ("MATCH".equals(type)) {
                matched++;
            } else if ("DISCREPANCY".equals(type) || "UNREGISTERED".equals(type)) {
                discrepancy++;
            } else if ("MISSING".equals(type) || "UNCHECKED".equals(type)) {
                missing++;
            }
        }

        run.setStatus("COMPLETED");
        run.setMatchedCount(matched);
        run.setDiscrepancyCount(discrepancy);
        run.setMissingCount(missing);
        run.setCompletedAt(LocalDateTime.now());
        updateById(run);

        log.info("Inventory run completed: id={}, matched={}, discrepancy={}, missing={}",
                runId, matched, discrepancy, missing);
        return run;
    }

    @Override
    public List<AssetInventoryItem> getItemsByRunId(Long runId) {
        return assetInventoryItemMapper.selectByRunId(runId);
    }

    @Override
    public IPage<AssetInventoryRun> searchRuns(int page, int size, String status) {
        Page<AssetInventoryRun> pageable = new Page<>(page, size);
        LambdaQueryWrapper<AssetInventoryRun> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(AssetInventoryRun::getStatus, status);
        }
        wrapper.orderByDesc(AssetInventoryRun::getId);
        return page(pageable, wrapper);
    }
}
