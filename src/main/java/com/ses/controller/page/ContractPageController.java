package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 契約画面コントローラー
 */
@Controller
@RequestMapping("/contract")
public class ContractPageController {

    /**
     * 契約一覧画面表示
     *
     * @return 画面パス
     */
    @GetMapping("/list")
    public String list() {
        return "contract/list";
    }

    /**
     * ガントチャート画面表示
     *
     * @return 画面パス
     */
    @GetMapping("/gantt")
    public String gantt() {
        return "contract/gantt";
    }
}
