package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 要員マイポータル 貸与資産・アカウント画面コントローラー
 */
@Controller
@RequestMapping("/my/assets")
@PreAuthorize("hasRole('要員')")
public class MyAssetPageController {

    @GetMapping
    public String index(Model model) {
        model.addAttribute("activeMenu", "my-assets");
        model.addAttribute("pageTitle", "貸与資産・アカウント一覧");
        return "my/assets";
    }
}
