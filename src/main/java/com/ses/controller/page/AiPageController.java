package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * AIマッチングコントローラー
 */
@Controller
@RequestMapping("/ai")
public class AiPageController {

    /**
     * AIマッチング画面
     */
    @GetMapping("/matching")
    public String matching(Model model) {
        model.addAttribute("currentUri", "/ai/matching");
        return "ai/matching";
    }
}
