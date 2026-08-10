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

    /**
     * 契約更新カレンダー画面表示（FR-06）
     *
     * @return 画面パス
     */
    @GetMapping("/renewal-calendar")
    public String renewalCalendar() {
        return "contract/renewal-calendar";
    }

    /**
     * 契約詳細画面表示（T063 A1: compliance profile/findings）。
     * データはJSが /api/contracts/{id}/compliance-profile から取得する。
     *
     * @return 画面パス
     */
    @GetMapping("/detail/{id}")
    public String detail() {
        return "contract/detail";
    }
}
