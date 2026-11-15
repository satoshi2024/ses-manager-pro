package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * NF-06 取込センター画面（W3-12）。view名だけを返す。
 *
 * <p>画面は既存の {@code /api/import-center/**} だけを呼ぶ。認可（管理者role＋明示action
 * {@code import.metadata.manage}）・法人scope・監査はAPIのservice側で操作ごとに再確認する。
 * 公開可否は機能スイッチ NF06（OFFなら {@code FeatureSwitchFilter} が404）、到達は {@code SecurityConfig} の
 * 管理者限定と {@code MenuPermissionFilter} が決める。
 */
@Controller
@RequestMapping("/import-center")
public class ImportCenterPageController {

    @GetMapping
    public String index() {
        return "import-center/index";
    }
}
