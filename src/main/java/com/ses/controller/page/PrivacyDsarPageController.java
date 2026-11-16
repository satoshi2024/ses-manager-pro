package com.ses.controller.page;

import com.ses.common.exception.BusinessException;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.dsar.PrivacyRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * NF07 DSAR の画面（{@code /privacy/dsar} 一覧・受付、{@code /privacy/dsar/{id}} 詳細・本人確認・名寄せ・範囲確定）。
 * 表示内容は {@code /api/privacy/dsar} から取得する。
 *
 * <p>画面の入口でも service 層の再認可（{@link PrivacyRequestService#requirePageAccess}）を通す。公開スイッチ OFF は404、
 * {@code privacy.dsar.view} が無ければ403 として統一エラー画面へ渡す（{@code MenuPermissionFilter} が取れない slice でも開かない）。
 */
@Controller
public class PrivacyDsarPageController {

    private final PrivacyRequestService requestService;

    public PrivacyDsarPageController(PrivacyRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping("/privacy/dsar")
    public String list() {
        requirePageAccess();
        return "privacy/dsar/list";
    }

    @GetMapping("/privacy/dsar/{id}")
    public String detail(@PathVariable long id, Model model) {
        requirePageAccess();
        if (id <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("requestId", id);
        return "privacy/dsar/detail";
    }

    private void requirePageAccess() {
        try {
            requestService.requirePageAccess();
        } catch (PrivacyAccessGuard.PrivacyEntryNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (BusinessException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}
