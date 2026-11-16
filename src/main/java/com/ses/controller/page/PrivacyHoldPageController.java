package com.ses.controller.page;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.hold.PrivacyHoldService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * NF07 保全 hold 画面（{@code /privacy/holds}）。表示内容は {@code /api/privacy/holds} から取得する。
 *
 * <p>画面の入口でも service 層の再認可（{@link PrivacyHoldService#requirePageAccess}）を通す。公開スイッチ OFF は404、
 * {@code privacy.hold.view} が無ければ403 として統一エラー画面へ渡す（{@code MenuPermissionFilter} が取れない slice でも開かない）。
 */
@Controller
public class PrivacyHoldPageController {

    private final PrivacyHoldService holdService;

    public PrivacyHoldPageController(PrivacyHoldService holdService) {
        this.holdService = holdService;
    }

    @GetMapping("/privacy/holds")
    public String holds() {
        try {
            holdService.requirePageAccess();
        } catch (PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (BusinessException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return "privacy/holds";
    }
}
