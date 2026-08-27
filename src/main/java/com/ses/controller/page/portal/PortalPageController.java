package com.ses.controller.page.portal;

import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * portal画面（/portal/**）。内部sidebar/layoutを流用しない専用画面（design §4）。
 */
@Controller
@RequiredArgsConstructor
public class PortalPageController {

    private final SystemConfigService systemConfigService;

    @GetMapping("/portal/login")
    public String login(@RequestParam(required = false) String returnUrl, Model model) {
        // メール通知等のリンクから戻るための相対URL（open redirect防止: design §5）。
        // 相対パス（先頭が単一の/）かつスキーム///を含まないものだけを許可する。
        model.addAttribute("returnUrl", safeReturnUrl(returnUrl));
        return "portal/login";
    }

    /** open redirect拒否: 相対パスのみ許可。不正値は既定の/portalへ。 */
    private String safeReturnUrl(String returnUrl) {
        if (returnUrl == null || returnUrl.isBlank()) {
            return "/portal";
        }
        String candidate = returnUrl.trim();
        if (!candidate.startsWith("/") || candidate.startsWith("//") || candidate.startsWith("/\\")
                || candidate.contains("://") || candidate.contains("\\")) {
            return "/portal";
        }
        return candidate;
    }

    @GetMapping("/portal/accept-invitation")
    public String acceptInvitation() {
        return "portal/accept-invitation";
    }

    @GetMapping("/portal/terms")
    public String terms(Model model) {
        model.addAttribute("termsVersion",
                systemConfigService.getString("portal.terms.current-version", "1"));
        return "portal/terms";
    }

    @GetMapping("/portal")
    public String index() {
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof com.ses.portal.PortalLoginUser user) {
            if ("CUSTOMER".equals(user.getOrgType())) {
                return "redirect:/portal/customer";
            }
            if ("BP".equals(user.getOrgType())) {
                return "redirect:/portal/bp";
            }
        }
        return "redirect:/portal/login";
    }

    @GetMapping("/portal/customer")
    public String customer() {
        return "portal/customer/index";
    }

    @GetMapping("/portal/customer/service-desk/requests")
    public String customerServiceDeskList() {
        return "portal/customer/service-desk/list";
    }

    @GetMapping("/portal/customer/service-desk/requests/{id}")
    public String customerServiceDeskDetail(@org.springframework.web.bind.annotation.PathVariable Long id, Model model) {
        model.addAttribute("requestId", id);
        return "portal/customer/service-desk/detail";
    }

    @GetMapping("/portal/bp")
    public String bp() {
        return "portal/bp/index";
    }
}
