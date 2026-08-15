package com.ses.controller.page;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/analytics")
public class AnalyticsPageController {

    @GetMapping
    public String index() {
        return "analytics/index";
    }

    @GetMapping("/availability-calendar")
    public String availabilityCalendar() {
        return "analytics/availability-calendar";
    }

    @GetMapping("/staffing-heatmap")
    public String staffingHeatmap() {
        return "analytics/staffing-heatmap";
    }

    @GetMapping("/staffing-scenario-compare")
    public String staffingScenarioCompare() {
        return "analytics/staffing-scenario-compare";
    }
}
