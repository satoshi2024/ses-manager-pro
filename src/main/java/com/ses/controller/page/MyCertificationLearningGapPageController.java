package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 要員本人の資格・学習計画ページ。データは本人APIから取得する。 */
@Controller
@RequestMapping("/my/certification-learning-skill-gap")
public class MyCertificationLearningGapPageController {

    @GetMapping({"", "/"})
    public String index() {
        return "my/certification-learning-skill-gap";
    }
}
