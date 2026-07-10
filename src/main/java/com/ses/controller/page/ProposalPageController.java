package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 提案画面コントローラー
 */
@Controller
@RequestMapping("/proposal")
public class ProposalPageController {

    /**
     * かんばん画面表示
     *
     * @return 画面パス
     */
    @GetMapping("/kanban")
    public String kanban() {
        return "proposal/kanban";
    }
}
