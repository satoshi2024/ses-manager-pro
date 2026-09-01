package com.ses.controller.page;

import com.ses.config.AiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 経営コパイロット chat 画面（A1）。 */
@Controller
@RequestMapping("/copilot")
@RequiredArgsConstructor
public class CopilotPageController {

    private final AiConfig aiConfig;

    @GetMapping({"", "/chat"})
    public String chat(Model model) {
        model.addAttribute("copilotEnabled", aiConfig.isManagementCopilotEnabled());
        return "copilot/chat";
    }
}
