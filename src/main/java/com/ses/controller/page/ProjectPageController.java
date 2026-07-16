package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 案件画面コントローラー
 */
@Controller
@RequestMapping("/project")
public class ProjectPageController {

    /**
     * 案件一覧画面
     */
    @GetMapping("/list")
    public String list() {
        return "project/list";
    }

    /**
     * 案件登録・編集画面
     */
    @GetMapping("/form")
    public String form() {
        return "project/form";
    }

}
