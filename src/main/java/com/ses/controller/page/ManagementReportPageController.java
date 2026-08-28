package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 定期管理レポート画面。データ取得と認可はAPI/service側で行う。 */
@Controller
@RequestMapping("/management-reports")
public class ManagementReportPageController {

    @GetMapping
    public String index() {
        return "management-reports/index";
    }
}
