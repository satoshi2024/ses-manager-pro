package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 管理会計ダッシュボード画面。集計値はAPIの組織scopeで取得する。 */
@Controller
@RequestMapping("/management-accounting")
public class ManagementAccountingPageController {

    @GetMapping({"", "/list"})
    public String index() {
        return "management-accounting/index";
    }
}
