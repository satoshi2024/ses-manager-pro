package com.ses.controller.api;

import com.ses.common.exception.BusinessException;
import com.ses.common.result.ApiResult;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.SavedView;
import com.ses.service.SavedViewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 保存ビュー REST API コントローラー
 */
@RestController
@RequestMapping("/api/saved-views")
public class SavedViewApiController {

    @Autowired
    private SavedViewService savedViewService;

    /**
     * 指定画面で利用可能な保存ビュー一覧取得（共有ビュー + ログインユーザー個人ビュー）
     */
    @GetMapping
    public ApiResult<List<SavedView>> getSavedViews(@RequestParam("pageKey") String pageKey) {
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        List<SavedView> views = savedViewService.getViewsForUserAndPage(userId, pageKey);
        return ApiResult.success(views);
    }

    /**
     * 保存ビューの新規保存
     */
    @PostMapping
    public ApiResult<SavedView> createSavedView(@RequestBody SavedView savedView) {
        Long userId = SecurityUtils.currentUserId();
        String userRole = SecurityUtils.currentRole();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        boolean isAdmin = "管理者".equals(userRole);
        SavedView created = savedViewService.createView(savedView, userId, isAdmin);
        return ApiResult.success(created);
    }

    /**
     * 保存ビューの更新
     */
    @PutMapping("/{id}")
    public ApiResult<SavedView> updateSavedView(@PathVariable("id") Long id, @RequestBody SavedView savedView) {
        Long userId = SecurityUtils.currentUserId();
        String userRole = SecurityUtils.currentRole();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        savedView.setId(id);
        boolean isAdmin = "管理者".equals(userRole);
        SavedView updated = savedViewService.updateView(savedView, userId, isAdmin);
        return ApiResult.success(updated);
    }

    /**
     * 保存ビューの削除
     */
    @DeleteMapping("/{id}")
    public ApiResult<Void> deleteSavedView(@PathVariable("id") Long id) {
        Long userId = SecurityUtils.currentUserId();
        String userRole = SecurityUtils.currentRole();
        if (userId == null) {
            throw new BusinessException(401, "認証が必要です");
        }
        boolean isAdmin = "管理者".equals(userRole);
        savedViewService.deleteView(id, userId, isAdmin);
        return ApiResult.success(null);
    }
}
