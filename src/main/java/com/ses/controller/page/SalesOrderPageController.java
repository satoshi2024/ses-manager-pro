package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 注文管理ページ（order-acceptance-workflow / A1）。 */
@Controller
@RequestMapping("/sales-order")
public class SalesOrderPageController {

    @GetMapping
    public String list() {
        return "sales-order/list";
    }
}
