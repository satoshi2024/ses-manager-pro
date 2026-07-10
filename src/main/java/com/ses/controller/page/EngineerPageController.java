package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * エンジニア画面コントローラー
 */
@Controller
@RequestMapping("/engineer")
public class EngineerPageController {

    /**
     * エンジニア一覧画面
     */
    @GetMapping("/list")
    public String list() {
        return "engineer/list";
    }

    /**
     * エンジニア登録・編集画面
     */
    @GetMapping("/form")
    public String form() {
        return "engineer/form";
    }

    /**
     * エンジニア詳細画面
     */
    @GetMapping("/detail")
    public String detail() {
        return "engineer/detail";
    }
}
