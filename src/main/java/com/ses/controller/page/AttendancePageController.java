package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import com.ses.common.util.SecurityUtils;

/** 雇用勤怠の本人/管理画面。客先工数のwork-record画面とは分離する。 */
@Controller
public class AttendancePageController {

    @GetMapping("/my/attendance")
    public String my(Model model) {
        model.addAttribute("attendanceMode", "my");
        return "attendance/my";
    }

    @GetMapping("/work-record/attendance")
    public String management(Model model) {
        model.addAttribute("attendanceMode", "management");
        String role = SecurityUtils.currentRole();
        model.addAttribute("canApprove", "管理者".equals(role) || "マネージャー".equals(role));
        model.addAttribute("canClose", "管理者".equals(role) || "HR".equals(role));
        model.addAttribute("canReopen", "管理者".equals(role));
        // T072: 外部同期の実行は管理者/HRのみ（マネージャーはstatus/CSV閲覧のみ、design §5.3）
        model.addAttribute("canSync", "管理者".equals(role) || "HR".equals(role));
        return "attendance/management";
    }
}
