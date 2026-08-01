package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** CRMリード・商機画面。 */
@Controller
@RequestMapping("/crm")
public class CrmPageController {
    @GetMapping("/leads")
    public String leads() {
        return "crm/leads";
    }

    @GetMapping("/opportunities")
    public String opportunities() {
        return "crm/opportunities";
    }

    @GetMapping("/opportunities/kpi")
    public String opportunityKpi() {
        return "crm/kpi";
    }
}
