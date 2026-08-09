package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 休暇画面（T071/A2）。本人申請と管理一覧を返す。ロール境界はSecurityConfigとmenu権限が担う。 */
@Controller
public class LeavePageController {

    @GetMapping("/my/leave")
    public String myLeave() {
        return "leave/my";
    }

    @GetMapping("/leave")
    public String management() {
        return "leave/management";
    }
}
