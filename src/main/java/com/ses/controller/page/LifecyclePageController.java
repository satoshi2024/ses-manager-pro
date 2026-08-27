package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ライフサイクル管理ページコントローラー
 */
@Controller
@RequestMapping("/lifecycle")
public class LifecyclePageController {

    /**
     * ライフサイクル案件一覧画面
     */
    @GetMapping({"", "/list"})
    public String list(Model model) {
        model.addAttribute("pageTitle", "ライフサイクル管理");
        return "lifecycle/list";
    }

    /**
     * ライフサイクル案件詳細・タスク実行画面
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable("id") Long id, Model model) {
        model.addAttribute("pageTitle", "ライフサイクル案件詳細");
        model.addAttribute("caseId", id);
        return "lifecycle/detail";
    }

    /**
     * テンプレート管理画面
     */
    @GetMapping("/templates")
    public String templates(Model model) {
        model.addAttribute("pageTitle", "ライフサイクルテンプレート管理");
        return "lifecycle/templates";
    }
}
