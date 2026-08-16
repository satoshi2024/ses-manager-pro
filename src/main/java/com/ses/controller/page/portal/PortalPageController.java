package com.ses.controller.page.portal;

import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * portal画面（/portal/**）。内部sidebar/layoutを流用しない専用画面（design §4）。
 */
@Controller
@RequiredArgsConstructor
public class PortalPageController {

    private final SystemConfigService systemConfigService;

    @GetMapping("/portal/login")
    public String login() {
        return "portal/login";
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
}
