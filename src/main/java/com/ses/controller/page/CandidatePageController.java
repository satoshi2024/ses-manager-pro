package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 候補者画面コントローラー
 */
@Controller
@RequestMapping("/candidate")
public class CandidatePageController {

    /**
     * 候補者一覧画面
     */
    @GetMapping("/list")
    public String list() {
        return "candidate/list";
    }

    /**
     * 候補者詳細画面
     */
    @GetMapping("/detail")
    public String detail() {
        return "candidate/detail";
    }
}
