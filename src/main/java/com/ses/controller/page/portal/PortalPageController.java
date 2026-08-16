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
        return "portal/index";
    }
}
