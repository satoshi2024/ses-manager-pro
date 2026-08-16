package com.ses.controller.page.portaladmin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * portal管理画面（/portal-admin。B1）。内部layout/baseを使用する（管理者・営業用）。
 */
@Controller
public class PortalAdminPageController {

    @GetMapping("/portal-admin")
    public String list() {
        return "portal-admin/list";
    }
}
