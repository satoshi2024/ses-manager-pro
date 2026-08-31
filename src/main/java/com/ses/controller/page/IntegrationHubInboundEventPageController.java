package com.ses.controller.page;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** NF-05 inbound event/DLQ管理画面。raw body/snapshotは表示しない。 */
@Controller
@RequestMapping("/integration-hub/inbound-events")
@PreAuthorize("hasRole('管理者')")
public class IntegrationHubInboundEventPageController {
    @GetMapping
    public String index() {
        return "integration-hub/inbound-events";
    }
}
