package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 旧メニュー名（/tasks）からの互換入口。実体はToDo画面で管理する。 */
@Controller
@RequestMapping("/tasks")
public class TasksPageController {

    @GetMapping
    public String alias() {
        return "redirect:/todo";
    }
}
