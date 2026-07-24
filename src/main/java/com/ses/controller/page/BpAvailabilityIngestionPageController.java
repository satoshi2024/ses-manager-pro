package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 外部要員在庫メール取込 画面コントローラー。
 */
@Controller
@RequestMapping("/bp-availability-ingestion")
public class BpAvailabilityIngestionPageController {

    @GetMapping
    public String list() {
        return "bp-availability-ingestion/list";
    }

    @GetMapping("/review/{id}")
    public String review(@PathVariable Long id) {
        return "bp-availability-ingestion/review";
    }
}
