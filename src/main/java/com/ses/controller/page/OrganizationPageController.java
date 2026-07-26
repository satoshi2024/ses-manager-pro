package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 組織管理画面。業務データはAPIの組織scopeで取得する。 */
@Controller
@RequestMapping("/organization")
public class OrganizationPageController {

    @GetMapping({"", "/list"})
    public String list() {
        return "organization/list";
    }
}
