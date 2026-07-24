package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 外部要員在庫 画面コントローラー。
 */
@Controller
@RequestMapping("/bp-availability")
public class BpAvailabilityPageController {

    @GetMapping("/list")
    public String list() {
        return "bp-availability/list";
    }
}
