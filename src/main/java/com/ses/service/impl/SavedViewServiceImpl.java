package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.SavedView;
import com.ses.mapper.SavedViewMapper;
import com.ses.service.SavedViewSchemaRegistry;
import com.ses.service.SavedViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class SavedViewServiceImpl extends ServiceImpl<SavedViewMapper, SavedView> implements SavedViewService {

    @Autowired
    private SavedViewSchemaRegistry schemaRegistry;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SavedView createView(SavedView view, Long currentUserId, boolean isAdmin) {
        validateViewInput(view);

        boolean isShared = (view.getSharedFlag() != null && view.getSharedFlag() == 1);

        if (isShared) {
            if (!isAdmin) {
                throw new BusinessException(403, "共有ビューを作成できるのは管理者のみです");
            }
            view.setOwnerUserId(null); // 明示的にNULL＝共有
            view.setSharedFlag(1);
        } else {
            view.setOwnerUserId(currentUserId);
            view.setSharedFlag(0);
        }

        save(view);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SavedView updateView(SavedView view, Long currentUserId, boolean isAdmin) {
        if (view.getId() == null) {
            throw new BusinessException(400, "更新対象のIDが指定されていません");
        }

        SavedView existing = getById(view.getId());
        if (existing == null) {
            throw new BusinessException(404, "保存ビューが見つかりません: " + view.getId());
        }

        validateViewInput(view);

        // 共有ビューの判定
        boolean isExistingShared = existing.getOwnerUserId() == null || (existing.getSharedFlag() != null && existing.getSharedFlag() == 1);

        if (isExistingShared) {
            if (!isAdmin) {
                throw new BusinessException(403, "共有ビューを更新できるのは管理者のみです");
            }
        } else {
            // 個人ビューの場合: 管理者であっても他人の個人ビューは上書き不可 (R3.2)
            if (!currentUserId.equals(existing.getOwnerUserId())) {
                throw new BusinessException(403, "他ユーザーの個人ビューを上書き・更新することはできません");
            }
        }
        if (view.getVersion() != null && !view.getVersion().equals(existing.getVersion())) {
            throw new BusinessException(409, "保存ビューの更新に失敗しました（バージョン競合）");
        }

        existing.setName(view.getName());
        existing.setFilterJson(view.getFilterJson());
        existing.setSortJson(view.getSortJson());
        existing.setColumnsJson(view.getColumnsJson());
        if (view.getPageSize() != null) {
            existing.setPageSize(view.getPageSize());
        }

        boolean updated = updateById(existing);
        if (!updated) {
            throw new BusinessException(409, "保存ビューの更新に失敗しました（同時更新の可能性があります）");
        }
        return existing;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteView(Long viewId, Long currentUserId, boolean isAdmin) {
        SavedView existing = getById(viewId);
        if (existing == null) {
            throw new BusinessException(404, "保存ビューが見つかりません: " + viewId);
        }

        boolean isShared = existing.getOwnerUserId() == null || (existing.getSharedFlag() != null && existing.getSharedFlag() == 1);

        if (isShared) {
            if (!isAdmin) {
                throw new BusinessException(403, "共有ビューを削除できるのは管理者のみです");
            }
        } else {
            // 個人ビューの場合: 管理者であっても他人の個人ビューは削除不可 (R3.2)
            if (!currentUserId.equals(existing.getOwnerUserId())) {
                throw new BusinessException(403, "他ユーザーの個人ビューを削除することはできません");
            }
        }

        removeById(viewId);
    }

    @Override
    public List<SavedView> getViewsForUserAndPage(Long userId, String pageKey) {
        if (!StringUtils.hasText(pageKey)) {
            return List.of();
        }
        LambdaQueryWrapper<SavedView> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SavedView::getPageKey, pageKey);

        if (userId != null) {
            // 個人ビュー(owner_user_id == userId) OR 共有ビュー(owner_user_id IS NULL)
            wrapper.and(w -> w.eq(SavedView::getOwnerUserId, userId).or().isNull(SavedView::getOwnerUserId));
        } else {
            wrapper.isNull(SavedView::getOwnerUserId);
        }

        wrapper.orderByDesc(SavedView::getSharedFlag).orderByAsc(SavedView::getName);
        return list(wrapper);
    }

    private void validateViewInput(SavedView view) {
        if (!StringUtils.hasText(view.getPageKey())) {
            throw new BusinessException(400, "ページキーは必須です");
        }
        if (!StringUtils.hasText(view.getName())) {
            throw new BusinessException(400, "ビュー名は必須です");
        }

        // Schema Registry allowlist 検証
        schemaRegistry.validateJsonContent(view.getPageKey(), view.getFilterJson());
        schemaRegistry.validateJsonContent(view.getPageKey(), view.getSortJson());
        schemaRegistry.validateJsonContent(view.getPageKey(), view.getColumnsJson());
    }
}
