package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 資産・アカウント管理 画面コントローラー
 */
@Controller
@RequestMapping("/asset")
@PreAuthorize("hasAnyRole('管理者', 'HR', 'マネージャー', '営業')")
public class AssetPageController {

    @GetMapping("/list")
    public String list(Model model) {
        model.addAttribute("activeMenu", "asset-management");
        model.addAttribute("pageTitle", "資産台帳管理");
        return "asset/list";
    }

    @GetMapping("/inventory")
    public String inventory(Model model) {
        model.addAttribute("activeMenu", "asset-management");
        model.addAttribute("pageTitle", "資産棚卸し管理");
        return "asset/inventory";
    }

    @GetMapping("/accounts")
    public String accounts(Model model) {
        model.addAttribute("activeMenu", "asset-management");
        model.addAttribute("pageTitle", "外部アカウント・ライセンス管理");
        return "asset/accounts";
    }
}
