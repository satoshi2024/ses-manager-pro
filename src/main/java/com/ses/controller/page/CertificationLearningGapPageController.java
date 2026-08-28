package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** 資格・学習・skill gap管理画面。データ取得はAPIへ委譲する。 */
@Controller
@RequestMapping("/certification-learning-skill-gap")
public class CertificationLearningGapPageController {

    @GetMapping({"", "/list"})
    public String list() {
        return "certification-learning-skill-gap/list";
    }
}
