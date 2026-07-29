package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** break-glassのMFA登録・チャレンジ画面。 */
@Controller
public class MfaPageController {

    @GetMapping("/mfa/setup")
    public String setup() {
        return "mfa/setup";
    }

    @GetMapping("/mfa/challenge")
    public String challenge() {
        return "mfa/challenge";
    }
}
