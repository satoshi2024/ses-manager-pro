package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 画面「機能の開放」（feature-enablement-console design §15.2・FE-RQ-011）。管理者だけ（{@code SecurityConfig} の
 * {@code hasRole("管理者")}、menu {@code feature-enablement} は {@code 管理者} だけに付与）。データは API
 * （{@code /api/feature-enablement}）から読む。
 */
@Controller
@RequestMapping("/feature-enablement")
@org.springframework.security.access.prepost.PreAuthorize("hasRole('管理者')")
public class FeatureEnablementPageController {

    @GetMapping
    public String index() {
        return "feature-enablement/list";
    }
}
