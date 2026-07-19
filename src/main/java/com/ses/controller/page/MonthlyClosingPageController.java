package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/monthly-closing")
public class MonthlyClosingPageController {

    @GetMapping
    public String list(Model model) {
        // 締め完了/解除は管理者・マネージャーのみ（API側 requireCloserRole と一致）。
        // HR等には実行不能なボタンを表示しない（R3R-08）。
        String role = com.ses.common.util.SecurityUtils.currentRole();
        boolean closer = "管理者".equals(role) || "マネージャー".equals(role);
        model.addAttribute("canConfirm", closer);
        model.addAttribute("canReopen", closer);
        return "monthly-closing/list";
    }
}
