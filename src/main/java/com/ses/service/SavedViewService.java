package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.SavedView;

import java.util.List;

public interface SavedViewService extends IService<SavedView> {

    /**
     * 保存ビューの新規登録
     */
    SavedView createView(SavedView view, Long currentUserId, boolean isAdmin);

    /**
     * 保存ビューの更新
     */
    SavedView updateView(SavedView view, Long currentUserId, boolean isAdmin);

    /**
     * 保存ビューの削除
     */
    void deleteView(Long viewId, Long currentUserId, boolean isAdmin);

    /**
     * ページキーおよびユーザーに応じた参照可能なビュー一覧（個人ビュー＋共有ビュー）
     */
    List<SavedView> getViewsForUserAndPage(Long userId, String pageKey);
}
