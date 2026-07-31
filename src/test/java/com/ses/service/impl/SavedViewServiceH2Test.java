package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.SavedView;
import com.ses.service.SavedViewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class SavedViewServiceH2Test {

    @Autowired
    private SavedViewService savedViewService;

    @Test
    void testSavedViewAllowlistValidation() {
        SavedView invalidView = new SavedView();
        invalidView.setPageKey("engineer");
        invalidView.setName("不正なビュー");
        invalidView.setFilterJson("{\"select * from sys_user\": \"1\"}");

        assertThrows(BusinessException.class, () -> savedViewService.createView(invalidView, 100L, false));
    }

    @Test
    void testPersonalAndSharedViews() {
        // 1. 一般ユーザー A が個人ビューを作成
        SavedView userAView = new SavedView();
        userAView.setPageKey("engineer");
        userAView.setName("ユーザーAのビュー");
        userAView.setFilterJson("{\"status\":\"active\"}");

        SavedView createdA = savedViewService.createView(userAView, 100L, false);
        assertEquals(100L, createdA.getOwnerUserId());
        assertEquals(0, createdA.getSharedFlag());

        // 2. 管理者が共有ビューを作成
        SavedView sharedView = new SavedView();
        sharedView.setPageKey("engineer");
        sharedView.setName("全社標準ビュー");
        sharedView.setSharedFlag(1);
        sharedView.setFilterJson("{\"prefecture\":\"Tokyo\"}");

        SavedView createdShared = savedViewService.createView(sharedView, 1L, true);
        assertNull(createdShared.getOwnerUserId()); // 共有ビューは owner_user_id IS NULL
        assertEquals(1, createdShared.getSharedFlag());

        // 3. ユーザー B (200L) が参照した場合、共有ビューのみ取得（ユーザーAの個人ビューは取得されない）
        List<SavedView> viewsB = savedViewService.getViewsForUserAndPage(200L, "engineer");
        assertEquals(1, viewsB.size());
        assertEquals("全社標準ビュー", viewsB.get(0).getName());

        // 4. ユーザー A (100L) が参照した場合、個人ビュー＋共有ビューの両方が取得される
        List<SavedView> viewsA = savedViewService.getViewsForUserAndPage(100L, "engineer");
        assertEquals(2, viewsA.size());
    }

    @Test
    void testAdminCannotUpdateOtherUserPersonalView() {
        // ユーザー A (100L) が個人ビューを作成
        SavedView personalView = new SavedView();
        personalView.setPageKey("contract");
        personalView.setName("ユーザーA専用ビュー");
        personalView.setFilterJson("{\"contractStatus\":\"active\"}");
        SavedView created = savedViewService.createView(personalView, 100L, false);

        // 管理者 (1L, isAdmin=true) であってもユーザーAの個人ビューを更新しようとすると拒否される (R3.2)
        SavedView updateAttempt = new SavedView();
        updateAttempt.setId(created.getId());
        updateAttempt.setPageKey("contract");
        updateAttempt.setName("改ざん試行");
        updateAttempt.setFilterJson("{\"contractStatus\":\"active\"}");

        assertThrows(BusinessException.class, () -> savedViewService.updateView(updateAttempt, 1L, true));
        assertThrows(BusinessException.class, () -> savedViewService.deleteView(created.getId(), 1L, true));
    }
}
